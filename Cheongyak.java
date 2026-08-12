/*
 * 청약 알림 - 아파트(청약홈) + 공모주(38커뮤니케이션) 신규 건을 텔레그램으로 보낸다.
 *
 * 빌드 불필요. JDK 11 이상이면 소스 파일을 그대로 실행한다.
 *   java Cheongyak.java             평소 실행 (작업 스케줄러에 등록)
 *   java Cheongyak.java --dry       텔레그램 안 보내고 콘솔에만 출력
 *   java Cheongyak.java --selftest  파서 자체 점검
 *
 * 환경변수:
 *   TG_TOKEN       텔레그램 봇 토큰            (필수)
 *   TG_CHAT_ID     받을 채팅 ID                (필수)
 *   APPLYHOME_KEY  data.go.kr 개인 API 인증키  (없으면 아파트 건너뜀)
 *   APT_REGIONS    지역 필터, 예 "서울,경기"   (비우면 전체)
 *
 * 외부 라이브러리를 쓰지 않는다. JDK 에 JSON 파서가 없어서 응답을 정규식으로 읽는데,
 * 청약홈 응답이 중첩 없는 평면 구조라 가능하다(실제 응답 200건으로 확인).
 * 구조가 바뀌면 parseRecords 가 0건을 돌려주므로 "0건 수집" 로그로 드러난다.
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Cheongyak {

    static final String IPO_URL = "http://www.38.co.kr/html/fund/index.htm?o=k";
    static final String APT_URL =
            "https://api.odcloud.kr/api/ApplyhomeInfoDetailSvc/v1/getAPTLttotPblancDetail";

    /**
     * 알림 한 건.
     * key  중복 판단용
     * date 정렬용 청약 시작일(ISO). 모르면 빈 문자열
     * text 실제로 보낼 내용
     */
    record Item(String key, String date, String text) {}

    // ---------- HTTP ----------
    //
    // java.net.http.HttpClient 대신 HttpURLConnection 을 쓴다. HttpClient 는 내부적으로
    // NIO Selector 를 열면서 loopback 파이프를 만드는데, 사내 보안 정책으로 그게 막힌
    // 환경에서는 "Unable to establish loopback connection" 으로 아예 못 뜬다.
    // HttpURLConnection 은 평범한 소켓이라 그 제약을 안 탄다.

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setConnectTimeout(20_000);
        c.setReadTimeout(20_000);
        return c;
    }

    /** 응답 본문을 바이트로 읽는다. 에러 응답이면 본문을 예외 메시지에 담는다. */
    static byte[] body(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
            byte[] b = in == null ? new byte[0] : in.readAllBytes();
            if (code != 200) {
                throw new IOException("HTTP " + code + " - " + new String(b, StandardCharsets.UTF_8));
            }
            return b;
        } finally {
            c.disconnect();
        }
    }

    static byte[] get(String url) throws IOException {
        return body(open(url));
    }

    // ---------- 공모주 (38커뮤니케이션) ----------

    static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    static final Pattern CELL = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);
    static final Pattern TAG = Pattern.compile("<[^>]+>");

    static List<Item> parseIpo(String html) {
        List<Item> out = new ArrayList<>();
        int from = html.indexOf("summary=\"공모주 청약일정\"");
        if (from < 0) return out;
        int to = html.indexOf("</table>", from);
        String table = html.substring(from, to < 0 ? html.length() : to);

        Matcher rows = ROW.matcher(table);
        while (rows.find()) {
            List<String> c = new ArrayList<>();
            Matcher cells = CELL.matcher(rows.group(1));
            while (cells.find()) {
                c.add(TAG.matcher(cells.group(1)).replaceAll("").replace("&nbsp;", " ").trim());
            }
            // 헤더 / 광고 / 빈 행 걸러내기: 청약일 칸에 "~" 가 있는 행만 진짜 데이터다.
            if (c.size() < 6 || c.get(0).isEmpty() || !c.get(1).contains("~")) continue;
            out.add(new Item("ipo:" + c.get(0) + c.get(1), ipoDate(c.get(1)), String.join("\n",
                    "[공모주] " + c.get(0),
                    "청약일 " + c.get(1),
                    "공모가 " + or(c.get(3), "-"),
                    "주간사 " + or(c.get(5), "-"))));
        }
        return out;
    }

    /** "2026.09.16~09.17" 의 앞쪽 시작일만 ISO 로. 형식이 다르면 빈 문자열. */
    static String ipoDate(String range) {
        if (range.length() < 10) return "";
        String d = range.substring(0, 10).replace('.', '-');
        return d.matches("\\d{4}-\\d{2}-\\d{2}") ? d : "";
    }

    static List<Item> fetchIpo() throws IOException {
        return parseIpo(new String(get(IPO_URL), Charset.forName("EUC-KR")));
    }

    // ---------- 아파트 (청약홈) ----------

    /** 평면 객체 하나. 중첩이 없으므로 중괄호 안에 중괄호가 나오지 않는다. */
    static final Pattern RECORD = Pattern.compile("\\{[^{}]*\\}");
    static final Pattern FIELD = Pattern.compile(
            "\"([A-Z_0-9]+)\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,}\\s]+))");

    static List<Map<String, String>> parseRecords(String json) {
        List<Map<String, String>> out = new ArrayList<>();
        int i = json.indexOf("\"data\":[");
        if (i < 0) return out;
        int end = json.lastIndexOf(']');
        String arr = json.substring(i + 8, end < 0 ? json.length() : end);

        Matcher recs = RECORD.matcher(arr);
        while (recs.find()) {
            Map<String, String> m = new LinkedHashMap<>();
            Matcher fs = FIELD.matcher(recs.group());
            while (fs.find()) {
                String v = fs.group(2) != null ? unescape(fs.group(2)) : fs.group(3);
                m.put(fs.group(1), "null".equals(v) ? "" : v);
            }
            if (!m.isEmpty()) out.add(m);
        }
        return out;
    }

    static List<Item> parseApt(List<Map<String, String>> recs, List<String> regions, String today) {
        List<Item> out = new ArrayList<>();
        for (Map<String, String> r : recs) {
            String area = f(r, "SUBSCRPT_AREA_CODE_NM");
            if (!regions.isEmpty() && regions.stream().noneMatch(area::contains)) continue;

            String end = f(r, "RCEPT_ENDDE");
            // 이미 접수 마감된 공고는 알릴 이유가 없다. 날짜가 ISO 라 문자열 비교로 충분.
            if (!end.isEmpty() && end.compareTo(today) < 0) continue;

            String no = f(r, "PBLANC_NO"), name = f(r, "HOUSE_NM");
            if (no.isEmpty() || name.isEmpty()) continue;

            String bgn = f(r, "RCEPT_BGNDE"), win = f(r, "PRZWNER_PRESNATN_DE");
            List<String> lines = new ArrayList<>();
            lines.add("[아파트] " + name);
            lines.add(join(" · ", area, f(r, "HOUSE_SECD_NM")));
            lines.add(f(r, "HSSPLY_ADRES"));
            lines.add(prefix("공고일 ", f(r, "RCRIT_PBLANC_DE")));
            lines.add(bgn.isEmpty() && end.isEmpty() ? "" : "접수 " + bgn + " ~ " + end);
            lines.add(prefix("당첨발표 ", win));
            lines.add(f(r, "PBLANC_URL"));
            lines.removeIf(String::isBlank);
            out.add(new Item("apt:" + no, or(bgn, f(r, "RCRIT_PBLANC_DE")),
                    String.join("\n", lines)));
        }
        return out;
    }

    static List<Item> fetchApt(String key, List<String> regions, String today)
            throws IOException {
        // 공고일 최신순으로 내려오므로 앞쪽 200건이면 최근 몇 달을 덮는다.
        String url = APT_URL + "?page=1&perPage=200&serviceKey="
                + URLEncoder.encode(key, StandardCharsets.UTF_8);
        return parseApt(parseRecords(new String(get(url), StandardCharsets.UTF_8)), regions, today);
    }

    // ---------- 텔레그램 ----------

    static void send(String token, String chatId, String text) throws IOException {
        String payload = "{\"chat_id\":\"" + esc(chatId) + "\",\"text\":\"" + esc(text)
                + "\",\"disable_web_page_preview\":true}";
        HttpURLConnection c = open("https://api.telegram.org/bot" + token + "/sendMessage");
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
        }
        try {
            body(c);
        } catch (IOException e) {
            throw new IOException("텔레그램 전송 실패 - " + e.getMessage(), e);
        }
    }

    /** 텔레그램 메시지는 4096자 제한이라 묶어 보내되 넘치면 나눈다. */
    static void sendAll(String token, String chatId, List<Item> items) throws IOException {
        StringBuilder buf = new StringBuilder();
        for (Item it : items) {
            if (buf.length() > 0 && buf.length() + it.text().length() + 2 > 3800) {
                send(token, chatId, buf.toString());
                buf.setLength(0);
            }
            if (buf.length() > 0) buf.append("\n\n");
            buf.append(it.text());
        }
        if (buf.length() > 0) send(token, chatId, buf.toString());
    }

    // ---------- 정적 페이지 ----------
    //
    // GitHub Actions 가 수집한 결과를 여기서 HTML 로 떨궈 Pages 가 그대로 서빙한다.
    // 브라우저가 직접 API 를 호출하지 않으므로 CORS 도, 키 노출도 없다.

    static void writeHtml(Path out, List<Item> items) throws IOException {
        // 날짜 내림차순 = 다가오는 청약이 위로, 지난 건은 아래로.
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort((x, y) -> y.date().compareTo(x.date()));

        StringBuilder b = new StringBuilder();
        b.append("""
                <!DOCTYPE html>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>청약 알림</title>
                <style>
                  :root { color-scheme: light dark; }
                  body { font: 16px/1.6 system-ui, "Malgun Gothic", sans-serif;
                         max-width: 760px; margin: 0 auto; padding: 24px 16px 64px; }
                  h1 { font-size: 1.4rem; margin: 0 0 4px; }
                  .sub { color: #888; font-size: .85rem; margin-bottom: 28px; }
                  h2 { font-size: 1.1rem; margin: 32px 0 12px;
                       border-bottom: 2px solid currentColor; padding-bottom: 6px; }
                  .card { border: 1px solid #8883; border-radius: 10px;
                          padding: 14px 16px; margin-bottom: 10px; }
                  .card b { display: block; margin-bottom: 6px; font-size: 1.02rem; }
                  .card div { color: #777; font-size: .9rem; }
                  .card a { word-break: break-all; }
                  .soon { border-color: #e8a33d; background: #e8a33d18; }
                  .tag { float: right; font-size: .75rem; color: #e8a33d; font-weight: 600; }
                  .empty { color: #888; }
                </style>
                """);
        b.append("<h1>청약 알림</h1>\n<div class=\"sub\">갱신 ")
                .append(htmlEsc(java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
                        .format(java.time.format.DateTimeFormatter
                                .ofPattern("yyyy-MM-dd HH:mm"))))
                .append(" KST · 아파트는 청약홈, 공모주는 38커뮤니케이션</div>\n");

        String today = LocalDate.now().toString();
        section(b, "아파트", sorted, "apt:", today);
        section(b, "공모주", sorted, "ipo:", today);

        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(out, b.toString(), StandardCharsets.UTF_8);
        System.out.println("페이지 생성: " + out.toAbsolutePath().normalize());
    }

    static void section(StringBuilder b, String title, List<Item> all,
                        String prefix, String today) {
        b.append("<h2>").append(title).append("</h2>\n");
        boolean any = false;
        for (Item it : all) {
            if (!it.key().startsWith(prefix)) continue;
            any = true;
            String[] lines = it.text().split("\n");
            // 첫 줄은 "[아파트] 이름" 이라 말머리를 떼고 제목으로 쓴다.
            String head = lines[0].replaceFirst("^\\[[^\\]]*\\]\\s*", "");
            boolean soon = !it.date().isEmpty() && it.date().compareTo(today) >= 0;
            b.append("<div class=\"card").append(soon ? " soon" : "").append("\">");
            if (soon) b.append("<span class=\"tag\">예정</span>");
            b.append("<b>").append(htmlEsc(head)).append("</b>");
            for (int i = 1; i < lines.length; i++) {
                String l = lines[i];
                b.append("<div>").append(l.startsWith("http")
                        ? "<a href=\"" + htmlEsc(l) + "\">공고 보기</a>"
                        : htmlEsc(l)).append("</div>");
            }
            b.append("</div>\n");
        }
        if (!any) b.append("<p class=\"empty\">수집된 건이 없습니다.</p>\n");
    }

    static String htmlEsc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---------- 중복 방지 ----------
    // 한 줄에 키 하나. 키에 줄바꿈이 들어갈 일이 없어서 JSON 까지 갈 필요가 없다.

    // 소스 런처 모드에서는 getCodeSource() 가 소스 위치를 알려주지 않으므로
    // run.bat 이 -Dcheongyak.dir 로 넘겨준다. 직접 실행하면 현재 디렉토리에 둔다.
    static Path seenFile() {
        String dir = System.getProperty("cheongyak.dir");
        return (dir != null ? Paths.get(dir) : Paths.get("").toAbsolutePath())
                .resolve("seen.txt").normalize();
    }

    /** 첫 실행이면 null. */
    static Set<String> loadSeen() {
        Path p = seenFile();
        if (!Files.exists(p)) return null;
        try {
            Set<String> s = new TreeSet<>(Files.readAllLines(p, StandardCharsets.UTF_8));
            s.removeIf(String::isBlank);
            return s;
        } catch (IOException e) {
            return null;
        }
    }

    static void saveSeen(Set<String> seen) throws IOException {
        List<String> keep = new ArrayList<>(new TreeSet<>(seen));
        if (keep.size() > 5000) keep = keep.subList(keep.size() - 5000, keep.size());
        Files.write(seenFile(), keep, StandardCharsets.UTF_8);
    }

    // ---------- 메인 ----------

    public static void main(String[] args) throws Exception {
        List<String> a = Arrays.asList(args);
        if (a.contains("--selftest")) { selftest(); return; }

        String token = System.getenv("TG_TOKEN"), chat = System.getenv("TG_CHAT_ID");
        String aptKey = System.getenv("APPLYHOME_KEY");
        List<String> regions = new ArrayList<>();
        for (String g : or(System.getenv("APT_REGIONS"), "").split(",")) {
            if (!g.isBlank()) regions.add(g.trim());
        }
        boolean dry = a.contains("--dry");
        if (!dry && (isBlank(token) || isBlank(chat))) {
            System.err.println("TG_TOKEN / TG_CHAT_ID 환경변수를 설정하세요. (--dry 로 확인만 가능)");
            System.exit(1);
        }

        String today = LocalDate.now().toString();
        List<Item> items = new ArrayList<>();

        // 한쪽 소스가 죽어도 다른 쪽은 알린다.
        try {
            List<Item> got = fetchIpo();
            System.out.println("공모주: " + got.size() + "건 수집");
            items.addAll(got);
        } catch (Exception e) {
            System.out.println("공모주: 수집 실패 - " + e);
        }
        if (isBlank(aptKey)) {
            System.out.println("아파트: APPLYHOME_KEY 없음 - 건너뜀");
        } else {
            try {
                List<Item> got = fetchApt(aptKey, regions, today);
                System.out.println("아파트: " + got.size() + "건 수집");
                items.addAll(got);
            } catch (Exception e) {
                System.out.println("아파트: 수집 실패 - " + e);
            }
        }

        // 페이지는 중복 판정과 무관하게 "지금 열려 있는 전부"를 보여준다.
        String htmlPath = argValue(a, "--html");
        if (htmlPath != null) {
            // 두 소스가 동시에 실패하면 0건이 된다. 그걸로 멀쩡한 페이지를 덮으면 안 되고,
            // 조용히 넘어가서도 안 된다. 실패로 끝내야 Actions 가 알려준다.
            if (items.isEmpty()) {
                System.err.println("수집 0건. 페이지를 덮어쓰지 않고 실패로 끝냅니다.");
                System.exit(1);
            }
            writeHtml(Paths.get(htmlPath), items);
        }

        Set<String> seen = loadSeen();
        if (seen == null) {
            Set<String> all = new TreeSet<>();
            for (Item i : items) all.add(i.key());
            saveSeen(all);
            System.out.println("첫 실행: " + items.size()
                    + "건을 기준으로 등록했습니다. 다음 실행부터 신규 건만 알립니다.");
            System.out.println("중복 방지 파일: " + seenFile());
            return;
        }

        List<Item> fresh = new ArrayList<>();
        for (Item i : items) if (!seen.contains(i.key())) fresh.add(i);
        if (fresh.isEmpty()) { System.out.println("신규 없음"); return; }

        if (dry) {
            List<String> texts = new ArrayList<>();
            for (Item i : fresh) texts.add(i.text());
            System.out.println(String.join("\n\n", texts));
        } else {
            sendAll(token, chat, fresh);
            System.out.println(fresh.size() + "건 전송");
            for (Item i : fresh) seen.add(i.key());
            saveSeen(seen);   // 전송에 성공한 뒤에만 기록한다
        }
    }

    // ---------- 잡동사니 ----------

    static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** "--html docs/index.html" 처럼 플래그 바로 뒤에 오는 값. 없으면 null. */
    static String argValue(List<String> args, String flag) {
        int i = args.indexOf(flag);
        return i >= 0 && i + 1 < args.size() ? args.get(i + 1) : null;
    }

    static String or(String s, String alt) { return isBlank(s) ? alt : s; }
    static String f(Map<String, String> m, String k) { return or(m.get(k), "").trim(); }
    static String prefix(String p, String v) { return v.isEmpty() ? "" : p + v; }

    static String join(String sep, String... parts) {
        List<String> ok = new ArrayList<>();
        for (String p : parts) if (!isBlank(p)) ok.add(p);
        return String.join(sep, ok);
    }

    /** JSON 문자열 이스케이프 해제. 실제 응답엔 없었지만 들어와도 깨지지 않게 해둔다. */
    static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> b.append(n);   // \" \\ \/ 는 그대로
            }
        }
        return b.toString();
    }

    /** JSON 문자열 이스케이프. 주택명에 따옴표가 섞여도 요청이 깨지지 않게 한다. */
    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    // ---------- 자체 점검 ----------

    static void check(boolean ok, String what) {
        if (!ok) throw new IllegalStateException("selftest 실패: " + what);
    }

    static void selftest() {
        String html = "<table summary=\"공모주 청약일정\"><tr><td>구분</td></tr>"
                + "<tr><td>빅웨이브로보틱스</td><td>2026.09.16~09.17</td><td>-</td>"
                + "<td>20,000~24,000</td><td></td><td>유진투자증권</td><td></td></tr>"
                + "<tr><td>광고</td><td></td></tr></table>";
        List<Item> ipo = parseIpo(html);
        check(ipo.size() == 1, "광고/헤더 행 제외");
        check(ipo.get(0).key().equals("ipo:빅웨이브로보틱스2026.09.16~09.17"), "공모주 키");
        check(ipo.get(0).text().contains("유진투자증권")
                && ipo.get(0).text().contains("20,000~24,000"), "공모주 본문");

        String json = "{\"currentCount\":4,\"data\":["
                + "{\"PBLANC_NO\":\"1\",\"HOUSE_NM\":\"가나\",\"SUBSCRPT_AREA_CODE_NM\":\"서울\","
                + "\"RCRIT_PBLANC_DE\":\"2026-08-01\","      // 접수시작일 없음 → 공고일로 대체
                + "\"RCEPT_ENDDE\":\"2026-09-08\",\"NSPRC_NM\":null,\"TOT_SUPLY_HSHLDCO\":594},"
                + "{\"PBLANC_NO\":\"2\",\"HOUSE_NM\":\"다라\",\"SUBSCRPT_AREA_CODE_NM\":\"부산\","
                + "\"RCEPT_BGNDE\":\"2026-08-30\",\"RCEPT_ENDDE\":\"2026-09-08\"},"
                + "{\"PBLANC_NO\":\"3\",\"HOUSE_NM\":\"마감된곳\",\"SUBSCRPT_AREA_CODE_NM\":\"서울\","
                + "\"RCEPT_ENDDE\":\"2026-07-01\"},"
                + "{\"HOUSE_NM\":\"번호없음\",\"SUBSCRPT_AREA_CODE_NM\":\"서울\"}"
                + "],\"totalCount\":4}";
        List<Map<String, String>> recs = parseRecords(json);
        check(recs.size() == 4, "레코드 4건 파싱 (실제=" + recs.size() + ")");
        check(recs.get(0).get("HOUSE_NM").equals("가나"), "한글 값");
        check(recs.get(0).get("NSPRC_NM").isEmpty(), "null 을 빈 문자열로");
        check(recs.get(0).get("TOT_SUPLY_HSHLDCO").equals("594"), "숫자 값");

        String today = "2026-08-12";
        List<Item> seoul = parseApt(recs, List.of("서울"), today);
        check(seoul.size() == 1 && seoul.get(0).key().equals("apt:1"), "지역+마감 필터");
        check(parseApt(recs, List.of(), today).size() == 2, "필터 없어도 마감건/번호없는건 제외");

        check(esc("따\"옴\\표\n").equals("따\\\"옴\\\\표\\n"), "JSON 이스케이프");
        check(unescape("\\uD55C\\uAE00 \\\"인용\\\"").equals("한글 \"인용\""), "JSON 이스케이프 해제");

        // 정렬 키
        check(ipo.get(0).date().equals("2026-09-16"), "공모주 청약 시작일 추출");
        check(ipoDate("미정").isEmpty() && ipoDate("2026/09/16~09/17").isEmpty(), "형식 다르면 빈 값");
        check(seoul.get(0).date().equals("2026-08-01"), "접수시작일 없으면 공고일로 대체");
        check(parseApt(recs, List.of(), today).get(1).date().equals("2026-08-30"), "접수시작일 우선");
        check(htmlEsc("<a href=\"x\">&</a>")
                .equals("&lt;a href=&quot;x&quot;&gt;&amp;&lt;/a&gt;"), "HTML 이스케이프");
        check(argValue(List.of("--html", "docs/index.html"), "--html").equals("docs/index.html")
                && argValue(List.of("--html"), "--html") == null, "인자 값 파싱");

        System.out.println("selftest OK");
    }
}
