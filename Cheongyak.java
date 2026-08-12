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
 *   TG_CHAT_ID     받을 채팅 ID. 쉼표로 여러 명 가능. 그룹은 음수 ID 하나면 된다 (필수)
 *   APPLYHOME_KEY  data.go.kr 개인 API 인증키  (없으면 아파트 건너뜀)
 *   APT_REGIONS    지역 필터 (안 정하면 "서울,경기,인천". 전국을 보려면 "전국")
 *   WEB_URL        메시지 맨 끝에 붙일 웹 페이지 주소 (없으면 링크를 안 붙인다)
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

    // 청약홈 SUBSCRPT_AREA_CODE_NM 은 "서울" "경기" "인천" 처럼 짧은 표기다
    // ("서울특별시" 가 아니다). 실제 응답 1000건에서 확인한 값.
    static final String DEFAULT_REGIONS = "서울,경기,인천";

    /**
     * 알림 한 건.
     * key   중복 판단용
     * start 청약 접수 시작일(ISO). 모르면 빈 문자열
     * end   청약 접수 종료일(ISO). 모르면 빈 문자열 — 이때는 마감 판정에서 제외하지 않는다
     * text  실제로 보낼 내용
     */
    record Item(String key, String start, String end, String text) {}

    /** 0 = 접수 중(가장 급하다), 1 = 접수 예정. 마감된 건은 애초에 수집 단계에서 뺀다. */
    static int rank(Item it, String today) {
        return !it.start().isEmpty() && it.start().compareTo(today) > 0 ? 1 : 0;
    }

    /** 마감일이 지났는가. 종료일을 모르면 함부로 버리지 않는다. */
    static boolean closed(String end, String today) {
        return !end.isEmpty() && end.compareTo(today) < 0;
    }

    /** 접수 중 먼저(임박한 마감 순), 그다음 예정(빨리 시작하는 순). */
    static List<Item> sortForDisplay(List<Item> items, String today) {
        List<Item> out = new ArrayList<>(items);
        out.sort((x, y) -> {
            int r = Integer.compare(rank(x, today), rank(y, today));
            if (r != 0) return r;
            return rank(x, today) == 0
                    ? x.end().compareTo(y.end())        // 곧 마감하는 것부터
                    : x.start().compareTo(y.start());   // 곧 시작하는 것부터
        });
        return out;
    }

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

    static List<Item> parseIpo(String html, String today) {
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
            String bgn = ipoDate(c.get(1)), end = ipoEnd(c.get(1));
            if (closed(end, today)) continue;   // 이미 끝난 청약은 싣지 않는다
            // 말머리(종류)는 메시지 머리말과 페이지 섹션이 이미 알려주므로 붙이지 않는다.
            out.add(new Item("ipo:" + c.get(0) + c.get(1), bgn, end, String.join("\n",
                    c.get(0),
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

    /**
     * "2026.09.16~09.17" 의 뒤쪽 종료일을 ISO 로. 연도는 시작일에서 가져온다.
     * "2026.12.30~01.02" 처럼 연말을 넘기면 다음 해로 넘긴다.
     * 형식이 다르면 빈 문자열 — 그러면 마감 판정에서 제외되어 살아남는다(버리는 쪽이 더 위험).
     */
    static String ipoEnd(String range) {
        String bgn = ipoDate(range);
        int i = range.indexOf('~');
        if (bgn.isEmpty() || i < 0) return "";
        String md = range.substring(i + 1).trim().replace('.', '-');
        if (!md.matches("\\d{2}-\\d{2}")) return "";
        int year = Integer.parseInt(bgn.substring(0, 4));
        if (md.compareTo(bgn.substring(5)) < 0) year++;   // 12월 -> 1월
        return year + "-" + md;
    }

    static List<Item> fetchIpo(String today) throws IOException {
        return parseIpo(new String(get(IPO_URL), Charset.forName("EUC-KR")), today);
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
            if (closed(end, today)) continue;

            String no = f(r, "PBLANC_NO"), name = f(r, "HOUSE_NM");
            if (no.isEmpty() || name.isEmpty()) continue;

            String bgn = f(r, "RCEPT_BGNDE"), win = f(r, "PRZWNER_PRESNATN_DE");
            List<String> lines = new ArrayList<>();
            lines.add(name);
            lines.add(join(" · ", area, f(r, "HOUSE_SECD_NM")));
            lines.add(f(r, "HSSPLY_ADRES"));
            lines.add(prefix("공고일 ", f(r, "RCRIT_PBLANC_DE")));
            lines.add(bgn.isEmpty() && end.isEmpty() ? "" : "접수 " + bgn + " ~ " + end);
            lines.add(prefix("당첨발표 ", win));
            lines.add(f(r, "PBLANC_URL"));
            lines.removeIf(String::isBlank);
            out.add(new Item("apt:" + no, or(bgn, f(r, "RCRIT_PBLANC_DE")), end,
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

    static final String[][] SECTIONS = {{"apt:", "🏢 아파트"}, {"ipo:", "📈 공모주"}};
    static final String RULE = "─".repeat(16);
    static final int LIMIT = 3800;   // 텔레그램 상한은 4096. 여유를 둔다.

    /**
     * 보낼 메시지를 만든다. 종류별로 묶어 머리말을 달고, 맨 끝에 웹 주소를 붙인다.
     * 상한을 넘으면 여러 개로 나누고, 나뉜 덩어리에는 머리말을 다시 달아 맥락을 잃지 않게 한다.
     */
    static List<String> compose(List<Item> items, String webUrl, String today) {
        List<String> parts = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String[] sec : SECTIONS) {
            List<Item> sub = new ArrayList<>();
            for (Item i : items) if (i.key().startsWith(sec[0])) sub.add(i);
            if (sub.isEmpty()) continue;

            String title = sec[1] + " " + sub.size() + "건";
            String head = title + "\n" + RULE;
            boolean headPending = true;
            for (Item it : sub) {
                String body = withBadge(it, today);
                String block = (headPending ? head + "\n\n" : "") + body;
                if (buf.length() > 0 && buf.length() + block.length() + 2 > LIMIT) {
                    parts.add(buf.toString());
                    buf.setLength(0);
                    // 섹션 도중에 잘렸으면 새 덩어리에 머리말을 다시 단다.
                    if (!headPending) block = title + " (이어서)\n" + RULE + "\n\n" + body;
                }
                if (buf.length() > 0) buf.append("\n\n");
                buf.append(block);
                headPending = false;
            }
        }
        if (buf.length() > 0) parts.add(buf.toString());

        if (!parts.isEmpty() && !isBlank(webUrl)) {
            String tail = "\n\n" + RULE + "\n웹 브라우저에서 보기\n" + webUrl;
            int last = parts.size() - 1;
            if (parts.get(last).length() + tail.length() > LIMIT) parts.add(tail.trim());
            else parts.set(last, parts.get(last) + tail);
        }
        return parts;
    }

    /**
     * 수신자 여러 명에게 보낸다.
     *
     * 한 명이 실패해도(예: 그 사람이 봇에게 START 를 안 눌렀다) 나머지는 계속 보낸다.
     * 전원 실패일 때만 예외를 던져 seen.txt 기록을 막는다 — 그래야 다음 실행에 다시 시도한다.
     * 일부만 실패한 경우 기록은 남긴다. 안 그러면 성공한 사람이 같은 알림을 계속 다시 받는다.
     */
    static void sendAll(String token, List<String> chatIds, List<Item> items,
                        String webUrl, String today) throws IOException {
        List<String> parts = compose(items, webUrl, today);
        List<String> failed = new ArrayList<>();
        for (String id : chatIds) {
            try {
                for (String p : parts) send(token, id, p);
            } catch (IOException e) {
                failed.add(id + " → " + e.getMessage());
            }
        }
        if (failed.size() == chatIds.size()) {
            throw new IOException("수신자 전원 전송 실패: " + String.join(" / ", failed));
        }
        for (String s : failed) System.out.println("전송 실패(건너뜀): " + s);
    }

    // ---------- 정적 페이지 ----------
    //
    // GitHub Actions 가 수집한 결과를 여기서 HTML 로 떨궈 Pages 가 그대로 서빙한다.
    // 브라우저가 직접 API 를 호출하지 않으므로 CORS 도, 키 노출도 없다.

    static void writeHtml(Path out, List<Item> items, String today) throws IOException {
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
                  .open { border-color: #3da35d; background: #3da35d14; }
                  .urgent { border-color: #d94f4f; background: #d94f4f18; }
                  .tag { float: right; font-size: .75rem; font-weight: 700;
                         margin-left: 8px; white-space: nowrap; }
                  .t-urgent { color: #d94f4f; }
                  .t-open { color: #3da35d; }
                  .t-soon { color: #e8a33d; }
                  .empty { color: #888; }
                </style>
                """);
        b.append("<h1>청약 알림</h1>\n<div class=\"sub\">갱신 ")
                .append(htmlEsc(java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
                        .format(java.time.format.DateTimeFormatter
                                .ofPattern("yyyy-MM-dd HH:mm"))))
                .append(" KST · 아파트는 청약홈, 공모주는 38커뮤니케이션")
                .append(" · 접수 마감된 건은 빼고 보여줍니다</div>\n");

        section(b, "아파트", items, "apt:", today);   // 호출 쪽에서 정렬해서 넘긴다
        section(b, "공모주", items, "ipo:", today);

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
            String head = lines[0];   // 첫 줄이 이름
            String[] s = badge(it, today);   // [카드 클래스, 태그 클래스, 문구]
            b.append("<div class=\"card ").append(s[0]).append("\">")
                    .append("<span class=\"tag ").append(s[1]).append("\">")
                    .append(s[2]).append("</span>")
                    .append("<b>").append(htmlEsc(head)).append("</b>");
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

    /**
     * 상태 배지. [카드 클래스, 태그 클래스, 문구, 이모지].
     * 앞의 둘은 웹 페이지가, 뒤의 둘은 텔레그램 메시지가 쓴다.
     * 마감된 건은 수집 단계에서 이미 빠지므로 여기서는 다루지 않는다.
     */
    static String[] badge(Item it, String today) {
        if (rank(it, today) == 1) return new String[]{"", "t-soon", "예정", "🟠"};
        String tomorrow = LocalDate.parse(today).plusDays(1).toString();
        if (!it.end().isEmpty() && it.end().compareTo(tomorrow) <= 0) {
            return new String[]{"urgent", "t-urgent",
                    it.end().equals(today) ? "오늘 마감" : "내일 마감", "🔴"};
        }
        return new String[]{"open", "t-open", "접수중", "🟢"};
    }

    /** 메시지용 본문. 첫 줄(이름) 앞에 상태를 붙인다. */
    static String withBadge(Item it, String today) {
        String[] b = badge(it, today);
        int nl = it.text().indexOf('\n');
        String first = nl < 0 ? it.text() : it.text().substring(0, nl);
        String rest = nl < 0 ? "" : it.text().substring(nl);
        return b[3] + " " + b[2] + " · " + first + rest;
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

        String token = System.getenv("TG_TOKEN");
        List<String> chats = splitCsv(System.getenv("TG_CHAT_ID"));   // 쉼표로 여러 명
        String aptKey = System.getenv("APPLYHOME_KEY");
        // 기본은 수도권만. 전국을 보려면 APT_REGIONS=전국 으로 둔다.
        String regionEnv = or(System.getenv("APT_REGIONS"), DEFAULT_REGIONS);
        List<String> regions = regionEnv.trim().equals("전국")
                ? List.of() : splitCsv(regionEnv);
        boolean dry = a.contains("--dry");
        if (!dry && (isBlank(token) || chats.isEmpty())) {
            System.err.println("TG_TOKEN / TG_CHAT_ID 환경변수를 설정하세요. (--dry 로 확인만 가능)");
            System.exit(1);
        }

        String today = LocalDate.now().toString();
        List<Item> items = new ArrayList<>();

        // 한쪽 소스가 죽어도 다른 쪽은 알린다.
        try {
            List<Item> got = fetchIpo(today);
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
                System.out.println("아파트: " + got.size() + "건 수집 ("
                        + (regions.isEmpty() ? "전국" : String.join(",", regions)) + ")");
                items.addAll(got);
            } catch (Exception e) {
                System.out.println("아파트: 수집 실패 - " + e);
            }
        }

        // 접수 중인 것(임박한 마감 순)을 먼저, 그다음 예정. 페이지와 메시지가 같은 순서를 쓴다.
        items = sortForDisplay(items, today);

        // 페이지는 중복 판정과 무관하게 "지금 열려 있는 전부"를 보여준다.
        String htmlPath = argValue(a, "--html");
        if (htmlPath != null) {
            // 두 소스가 동시에 실패하면 0건이 된다. 그걸로 멀쩡한 페이지를 덮으면 안 되고,
            // 조용히 넘어가서도 안 된다. 실패로 끝내야 Actions 가 알려준다.
            if (items.isEmpty()) {
                System.err.println("수집 0건. 페이지를 덮어쓰지 않고 실패로 끝냅니다.");
                System.exit(1);
            }
            writeHtml(Paths.get(htmlPath), items, today);
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

        String webUrl = or(System.getenv("WEB_URL"), "");
        if (dry) {
            // 실제로 나갈 메시지 그대로 찍는다.
            System.out.println(String.join("\n\n──────── 다음 메시지 ────────\n\n",
                    compose(fresh, webUrl, today)));
        } else {
            sendAll(token, chats, fresh, webUrl, today);
            System.out.println(fresh.size() + "건 전송 (수신자 " + chats.size() + "명)");
            for (Item i : fresh) seen.add(i.key());
            saveSeen(seen);   // 전송에 성공한 뒤에만 기록한다
        }
    }

    // ---------- 잡동사니 ----------

    static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** "a, b ,c" -> [a, b, c]. null/빈칸은 걸러낸다. */
    static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        if (s != null) {
            for (String p : s.split(",")) if (!p.isBlank()) out.add(p.trim());
        }
        return out;
    }

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
        String today = "2026-08-12";

        String html = "<table summary=\"공모주 청약일정\"><tr><td>구분</td></tr>"
                + "<tr><td>빅웨이브로보틱스</td><td>2026.09.16~09.17</td><td>-</td>"
                + "<td>20,000~24,000</td><td></td><td>유진투자증권</td><td></td></tr>"
                + "<tr><td>끝난청약</td><td>2026.04.20~04.21</td><td>-</td>"
                + "<td>2,000~2,000</td><td></td><td>키움증권</td><td></td></tr>"
                + "<tr><td>광고</td><td></td></tr></table>";
        List<Item> ipo = parseIpo(html, today);
        check(ipo.size() == 1, "광고/헤더 행 제외 + 끝난 청약 제외 (실제=" + ipo.size() + ")");
        check(ipo.get(0).key().equals("ipo:빅웨이브로보틱스2026.09.16~09.17"), "공모주 키");
        check(ipo.get(0).text().contains("유진투자증권")
                && ipo.get(0).text().contains("20,000~24,000"), "공모주 본문");

        // 공모주 종료일: 연도는 시작일에서, 연말을 넘기면 다음 해로
        check(ipoEnd("2026.09.16~09.17").equals("2026-09-17"), "공모주 종료일");
        check(ipoEnd("2026.12.30~01.02").equals("2027-01-02"), "연말 넘기면 다음 해");
        check(ipoEnd("미정").isEmpty() && ipoEnd("2026.09.16~미정").isEmpty(), "형식 다르면 빈 값");
        check(!closed("", today), "종료일 모르면 버리지 않는다");
        check(closed("2026-08-11", today) && !closed("2026-08-12", today), "당일은 아직 안 끝났다");

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

        List<Item> seoul = parseApt(recs, List.of("서울"), today);
        check(seoul.size() == 1 && seoul.get(0).key().equals("apt:1"), "지역+마감 필터");
        check(parseApt(recs, List.of(), today).size() == 2, "필터 없어도 마감건/번호없는건 제외");
        check(parseApt(recs, List.of("서울", "경기", "인천"), today).size() == 1, "여러 지역 OR 매칭");
        check(Arrays.asList(DEFAULT_REGIONS.split(",")).equals(List.of("서울", "경기", "인천")),
                "기본 지역은 수도권 3곳");

        check(esc("따\"옴\\표\n").equals("따\\\"옴\\\\표\\n"), "JSON 이스케이프");
        check(unescape("\\uD55C\\uAE00 \\\"인용\\\"").equals("한글 \"인용\""), "JSON 이스케이프 해제");

        // 정렬 키
        check(ipo.get(0).start().equals("2026-09-16"), "공모주 청약 시작일 추출");
        check(ipoDate("미정").isEmpty() && ipoDate("2026/09/16~09/17").isEmpty(), "형식 다르면 빈 값");
        check(seoul.get(0).start().equals("2026-08-01"), "접수시작일 없으면 공고일로 대체");
        check(parseApt(recs, List.of(), today).get(1).start().equals("2026-08-30"), "접수시작일 우선");

        // 상태 판정과 정렬: 접수 중(임박한 마감 순) 먼저, 그다음 예정(빨리 시작하는 순)
        Item urgent = new Item("apt:u", "2026-08-01", "2026-08-12", "오늘마감");
        Item tmr    = new Item("apt:t", "2026-08-01", "2026-08-13", "내일마감");
        Item open   = new Item("apt:o", "2026-08-01", "2026-08-20", "접수중");
        Item soon1  = new Item("apt:s1", "2026-08-18", "2026-08-21", "곧시작");
        Item soon2  = new Item("apt:s2", "2026-08-31", "2026-09-08", "나중시작");
        check(badge(urgent, today)[2].equals("오늘 마감"), "오늘 마감 배지");
        check(badge(tmr, today)[2].equals("내일 마감"), "내일 마감 배지");
        check(badge(open, today)[2].equals("접수중"), "접수중 배지");
        check(badge(soon1, today)[2].equals("예정"), "예정 배지");
        check(badge(urgent, today)[0].equals("urgent") && badge(open, today)[0].equals("open"),
                "카드 강조 클래스");

        List<Item> order = sortForDisplay(List.of(soon2, open, soon1, urgent, tmr), today);
        List<String> keys = new ArrayList<>();
        for (Item i : order) keys.add(i.key());
        check(keys.equals(List.of("apt:u", "apt:t", "apt:o", "apt:s1", "apt:s2")),
                "접수중(마감임박순) -> 예정(시작순) 정렬, 실제=" + keys);
        check(htmlEsc("<a href=\"x\">&</a>")
                .equals("&lt;a href=&quot;x&quot;&gt;&amp;&lt;/a&gt;"), "HTML 이스케이프");
        check(argValue(List.of("--html", "docs/index.html"), "--html").equals("docs/index.html")
                && argValue(List.of("--html"), "--html") == null, "인자 값 파싱");

        // 수신자 여러 명
        check(splitCsv(" 111 ,222,, 333 ").equals(List.of("111", "222", "333")), "쉼표 목록 파싱");
        check(splitCsv(null).isEmpty() && splitCsv("  ").isEmpty(), "빈 목록");
        // 메시지 구성: 종류별 머리말 + 하단 웹 주소
        List<Item> mixed = List.of(new Item("apt:1", "2026-08-01", "2026-08-20", "가나아파트\n서울"),
                new Item("ipo:x", "2026-08-02", "2026-08-21", "가나전자\n청약일 ..."),
                new Item("ipo:y", "2026-08-03", "2026-08-22", "다라전자\n청약일 ..."));
        List<String> msg = compose(mixed, "https://example.com/", today);
        check(msg.size() == 1, "짧으면 한 통");
        String m = msg.get(0);
        check(m.startsWith("🏢 아파트 1건"), "아파트 머리말이 먼저");
        check(m.contains("📈 공모주 2건"), "공모주 머리말과 건수");
        check(m.indexOf("🏢") < m.indexOf("📈"), "아파트 -> 공모주 순서");
        check(m.endsWith("웹 브라우저에서 보기\nhttps://example.com/"), "맨 끝에 웹 주소");
        check(!m.contains("[아파트]") && !m.contains("[공모주]"), "말머리 중복 없음");
        check(!compose(mixed, "", today).get(0).contains("웹 브라우저에서 보기"),
                "WEB_URL 없으면 링크 없음");
        check(compose(List.of(), "https://example.com/", today).isEmpty(), "보낼 게 없으면 빈 목록");

        // 메시지에도 상태 배지가 붙는다
        check(withBadge(urgent, today).startsWith("🔴 오늘 마감 · 오늘마감"), "메시지 배지 - 오늘 마감");
        check(withBadge(open, today).startsWith("🟢 접수중 · 접수중"), "메시지 배지 - 접수중");
        check(withBadge(soon1, today).startsWith("🟠 예정 · 곧시작"), "메시지 배지 - 예정");
        check(withBadge(new Item("apt:x", "2026-08-01", "2026-08-20", "이름\n둘째줄\n셋째줄"), today)
                .endsWith("\n둘째줄\n셋째줄"), "첫 줄에만 붙고 나머지는 그대로");
        check(m.contains("🟢 접수중 · 가나아파트"), "실제 메시지에 배지 반영");

        // 상한 초과 시 분할 + 잘린 섹션에 머리말 재부착
        List<Item> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(new Item("ipo:k" + i, "2026-01-01", "2026-12-31", "가".repeat(60)));
        }
        List<String> parts = compose(many, "https://example.com/", today);
        check(parts.size() > 1, "4096자 넘으면 나눠 보낸다");
        for (String p : parts) check(p.length() <= LIMIT, "덩어리 크기 제한 (" + p.length() + ")");
        check(parts.get(1).startsWith("📈 공모주 200건 (이어서)"), "이어지는 덩어리에 머리말 재부착");
        check(parts.get(parts.size() - 1).endsWith("https://example.com/"), "웹 주소는 마지막에 한 번");

        System.out.println("selftest OK");
    }
}
