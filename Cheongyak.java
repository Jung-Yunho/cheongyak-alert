/*
 * 청약 알림 - 아파트 분양 + 무순위·잔여세대 + 임의공급(청약홈)의 신규 공고를 알린다.
 *
 * 빌드 불필요. JDK 11 이상이면 소스 파일을 그대로 실행한다.
 *   java Cheongyak.java             평소 실행 (작업 스케줄러에 등록)
 *   java Cheongyak.java --dry       텔레그램 안 보내고 콘솔에만 출력
 *   java Cheongyak.java --selftest  파서 자체 점검
 *
 * 환경변수:
 *   TG_TOKEN       텔레그램 봇 토큰            (필수)
 *   TG_CHAT_ID     받을 채팅 ID. 쉼표로 여러 명 가능. 그룹은 음수 ID 하나면 된다 (필수)
 *   APPLYHOME_KEY  data.go.kr 개인 API 인증키  (필수)
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

    static final String BASE = "https://api.odcloud.kr/api/ApplyhomeInfoDetailSvc/v1/";

    /**
     * 공고 종류마다 엔드포인트가 따로 있다. 분양정보(Detail)에는 금액이 없어
     * 주택형별 상세(Mdl)를 한 번 더 부른다 — 주택형마다 분양가가 다르기 때문이다.
     *
     * label  로그에 찍을 이름
     * prefix seen.txt 키 접두어. 두 소스의 공고번호가 겹쳐도 서로 안 섞이게 한다
     */
    record Source(String label, String detail, String mdl, String prefix) {}

    static final Source[] SOURCES = {
            new Source("아파트 분양", BASE + "getAPTLttotPblancDetail",
                    BASE + "getAPTLttotPblancMdl", "apt:"),
            // 무순위·잔여세대. 대부분 당일 접수(하루짜리)라 오히려 놓치기 쉽다.
            new Source("무순위·잔여세대", BASE + "getRemndrLttotPblancDetail",
                    BASE + "getRemndrLttotPblancMdl", "rem:"),
            // 임의공급. 무순위에서도 남은 물량을 선착순으로 푸는 단계다. 이것도 아파트다.
            new Source("임의공급", BASE + "getOPTLttotPblancDetail",
                    BASE + "getOPTLttotPblancMdl", "opt:"),
    };

    // 청약홈 SUBSCRPT_AREA_CODE_NM 은 "서울" "경기" "인천" 처럼 짧은 표기다
    // ("서울특별시" 가 아니다). 실제 응답 1000건에서 확인한 값.
    static final String DEFAULT_REGIONS = "서울,경기,인천";

    /** 분양가를 낼 때 뺄 소형 타입의 기준(전용 ㎡ 이하). MIN_AREA 환경변수로 조정한다. */
    static final double MIN_AREA = parseArea(System.getenv("MIN_AREA"), 50);

    static double parseArea(String v, double dflt) {
        try {
            return isBlank(v) ? dflt : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            System.out.println("MIN_AREA 값을 못 읽어 기본값 " + dflt + " 을 씁니다: " + v);
            return dflt;
        }
    }

    /**
     * 알림 한 건.
     * key   중복 판단용
     * start 청약 접수 시작일(ISO). 모르면 빈 문자열
     * end   청약 접수 종료일(ISO). 모르면 빈 문자열 — 이때는 마감 판정에서 제외하지 않는다
     * sido  시·도. 웹 페이지 필터용
     * gugun 시·군·구. 위와 같음. 주소에서 못 읽으면 빈 문자열
     * addr  공급 주소. text 안에도 있지만, 페이지에서 이 줄만 지도 링크로 만들려고 따로 둔다
     * price 분양가 범위. 없으면 빈 문자열
     * area  그 분양가에 해당하는 전용면적 범위. 소형을 뺐다는 걸 알 수 있게 같이 보여준다
     * units 공급 세대수. 없으면 빈 문자열
     * text  텔레그램으로 보낼 내용 (위 값들이 줄 단위로 다 들어 있다)
     *
     * price/units 를 따로 두는 건 페이지에서 이 둘만 크게 강조하기 위해서다.
     * 값을 판단하는 데 제일 먼저 보게 되는 정보라 본문 줄과 섞어두면 눈에 안 들어온다.
     */
    record Item(String key, String start, String end, String sido, String gugun,
                String addr, String price, String area, String units, String text) {}

    /** 0 = 접수 중(가장 급하다), 1 = 접수 예정. 마감된 건은 애초에 수집 단계에서 뺀다. */
    static int rank(Item it, String today) {
        return !it.start().isEmpty() && it.start().compareTo(today) > 0 ? 1 : 0;
    }

    /**
     * 공고의 현재 상태. soon(예정) / open(접수중) / d2(모레 마감) / d1(내일 마감) / d0(오늘 마감).
     *
     * 이 값이 중복 판정 키에 들어간다. 공고 번호만으로 판정하면 **한 공고당 평생 한 번**만
     * 알리게 되는데, 보통 공고가 뜨는 건 접수 며칠 전이라 "예정" 알림 하나 받고 끝난다.
     * 정작 접수가 시작될 때도, 마감 전날에도 아무 소식이 없어 놓치기 쉽다.
     *
     * 마감 3일 전부터는 매일 상태가 바뀌므로 연달아 알림이 간다. 수도권 414건을 재보니
     * 접수 기간이 1일 47% · 2일 18% · 3일 15% · 4일 9% 로 **88%가 4일 이내**라,
     * 이것만으로 대부분 매일 알림이 된다. "접수중이면 무조건 매일" 로 하면 나머지 12%를
     * 위해 같은 목록을 매일 반복하게 되고, 반복되면 안 읽게 되어 정작 마감일 알림도 흘린다.
     */
    static String stateOf(Item it, String today) {
        if (rank(it, today) == 1) return "soon";
        if (it.end().isEmpty()) return "open";
        LocalDate t = LocalDate.parse(today);
        if (it.end().equals(today)) return "d0";
        if (it.end().equals(t.plusDays(1).toString())) return "d1";
        if (it.end().equals(t.plusDays(2).toString())) return "d2";
        return "open";
    }

    /** 중복 판정용 키. 공고 식별자 + 상태. Item.key() 자체는 공고 식별자로만 남겨둔다. */
    static String notifyKey(Item it, String today) {
        return it.key() + ":" + stateOf(it, today);
    }

    /**
     * 날짜를 ISO 로 맞춘다. 소스마다 형식이 다르다 —
     * 임의공급은 "20260813", 아파트 분양·무순위는 "2026-08-13" 을 준다.
     *
     * 섞이면 문자열 비교가 조용히 망가진다. "20260813" > "2026-08-12" 이라
     * (다섯째 글자에서 '0' > '-') 마감 판정이 통째로 무력화된다.
     */
    static String iso(String d) {
        return d.matches("\\d{8}")
                ? d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6) : d;
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

    /**
     * 공급 주소에서 시·군·구를 뽑는다. API 에 별도 필드가 없어 주소를 자른다.
     *   "서울특별시 영등포구 신길동 …"      -> 영등포구
     *   "경기도 성남시 수정구 신흥동 …"      -> 성남시   (특례시는 구가 하나 더 있지만 시까지만)
     *   "세종특별자치시 다솜동 …"            -> ""       (시·군·구 단계가 없다)
     * 형식이 어긋나면 빈 문자열. 그런 건은 필터에서 걸러내지 않고 항상 보여준다 —
     * 잘못 숨겨서 놓치는 쪽이 더 위험하다.
     */
    static String gugunOf(String addr) {
        String[] t = addr.trim().split("\\s+");
        if (t.length < 2) return "";
        return t[1].matches(".+[시군구]") ? t[1] : "";
    }

    /**
     * 지도 검색어. 공급 주소는 도로명이 아니라 지번 + 수식어라(수도권 231건 중 도로명은 16%)
     * 그대로 넘기면 잘 못 찾는다. 그래서 검색을 방해하는 것만 걷어낸다.
     *
     *  - 괄호 안이 번지나 도로명이면 그쪽이 더 정확하다. 바깥은 택지지구·단지 이름인 경우가 많다.
     *      "인천광역시 검단구 검단신도시 AB23BL(인천광역시 검단구 마전동 산175-7번지 일원)"
     *      "경기도 오산시 오산세교2지구 A-13블록 호반써밋 라프리미어(경기도 오산시 초평중앙로 65)"
     *  - 그 외의 괄호는 군더더기이므로 통째로 뗀다. 바깥이 이미 도로명인데 괄호 안이 지번인
     *    경우가 여기 해당한다: "경기도 광주시 초월읍 도곡길 27(쌍동리 402)"
     *  - "일원" / "일대" 는 행정 표현이라 검색에 방해만 된다.
     *  - 복수 번지는 첫 번째만: "거여동 181, 202번지" -> "거여동 181번지"
     *
     *  - 블록 코드("D1-1BL", "A-5블록")는 지도에 없는 표기라 검색을 방해한다. 뗀다.
     *
     * 그러고도 번지·도로명이 없으면 **행정구역까지만** 남긴다.
     *   "…원삼면 용인 반도체 클러스터 일반산업단지 D1-1BL" -> "경기도 용인시 처인구 원삼면"
     *   "…거모동, 군자동 시흥거모 공공주택지구 내 A-5블록"  -> "경기도 시흥시 거모동"
     *
     * 지구·단지명을 살려두면 지도 제공자에 따라 결과가 갈린다. 카카오는 "시흥거모
     * 공공주택지구"를 장소로 갖고 있어 잘 찾았지만 네이버는 없어서 "조건에 맞는 업체가
     * 없습니다" 가 뜨고 엉뚱한 위치를 보여준다. 행정구역은 어느 지도에나 있으니
     * 정확도를 조금 내주고 안정성을 택한다 — 틀린 위치를 보여주는 게 제일 나쁘다.
     * 단지 위치는 카드의 청약홈 공고 링크에서 확인할 수 있다.
     */
    static final Pattern INNER_ADDR =
            Pattern.compile("\\(([^()]*(?:번지|[로길]\\s?\\d)[^()]*)\\)");

    /** "D1-1BL", "A-5블록", "AB23BL" 같은 블록 지정. 지도에 없는 표기다. */
    static final Pattern BLOCK =
            Pattern.compile("\\s*\\(?[A-Za-z]{1,3}-?\\d+(?:-\\d+)?\\s*(?:BL|블록)\\)?");

    /**
     * 번지나 도로명이 들어 있는가. 없으면 지도가 단지를 못 짚는다.
     * 지번("81-8")과 블록 코드("D1-1BL")를 구별해야 해서 숫자-숫자 앞뒤에 영문이 붙으면 뺀다.
     */
    static final Pattern PINPOINT =
            Pattern.compile("번지|(?<![A-Za-z])\\d+-\\d+(?![A-Za-z])|[로길]\\s?\\d");

    /**
     * 앞에서부터 행정구역 토큰만 모은다. 시/도/군/구는 이어가고,
     * 읍/면/동/리를 만나면 그게 최말단이므로 거기서 끊는다.
     *   "경기도 시흥시 거모동, 군자동 시흥거모 공공주택지구" -> "경기도 시흥시 거모동"
     */
    static String adminOnly(String s) {
        List<String> out = new ArrayList<>();
        for (String raw : s.split("\\s+")) {
            String t = raw.replaceAll("[,.()]+$", "");
            if (t.matches(".+[시도군구]")) { out.add(t); continue; }
            if (t.matches(".+[읍면동리]")) { out.add(t); break; }
            break;
        }
        return String.join(" ", out);
    }

    /**
     * 네이버 지도 검색 주소. `/p/search/<검색어>` 가 정식이다(`/v5/` 는 302 로 넘어간다).
     *
     * 검색어가 **쿼리스트링이 아니라 경로**에 들어간다. 경로에서 `+` 는 공백이 아니라
     * 문자 그대로라, URLEncoder 가 만드는 `+` 를 `%20` 으로 바꿔야 한다.
     * 카카오(`?q=`)에서 그대로 옮기면 여기서 조용히 틀린다.
     */
    static String mapUrl(String addr) {
        String q = URLEncoder.encode(mapQuery(addr), StandardCharsets.UTF_8).replace("+", "%20");
        return "https://map.naver.com/p/search/" + q;
    }

    static String mapQuery(String addr) {
        String s = addr.trim();
        Matcher m = INNER_ADDR.matcher(s);
        if (m.find()) s = m.group(1);
        else s = s.replaceAll("\\([^()]*\\)", "");
        s = BLOCK.matcher(s).replaceAll("");
        s = s.replaceAll("\\s*(일원|일대)\\s*", " ");
        s = s.replaceAll("(\\d+)\\s*,\\s*\\d+(\\s*번지)", "$1$2");
        s = s.replaceAll("\\s+내\\s*$", "");   // "…지구 내" 처럼 남는 조사
        s = s.replaceAll("\\s+", " ").trim();

        if (PINPOINT.matcher(s).find()) return s;
        String admin = adminOnly(s);
        return admin.isEmpty() ? s : admin;
    }

    static List<Item> parseApt(List<Map<String, String>> recs, List<String> regions,
                               String today, String prefix) {
        return parseApt(recs, regions, today, prefix, Map.of());
    }

    /**
     * prices: 공고번호 -> "11.8억 ~ 14.5억". 없는 공고는 분양가 줄이 빠질 뿐이다.
     *
     * 접수일 필드명이 엔드포인트마다 다르다. 아파트 분양은 RCEPT_*, 무순위는 SUBSCRPT_RCEPT_*
     * 를 쓰고 드물게 GNRL_RCEPT_* 만 채워진 건도 있다(실제 60건 중 2건). 그래서 순서대로 찾는다.
     */
    static List<Item> parseApt(List<Map<String, String>> recs, List<String> regions,
                               String today, String prefix, Map<String, Price> prices) {
        List<Item> out = new ArrayList<>();
        for (Map<String, String> r : recs) {
            String region = f(r, "SUBSCRPT_AREA_CODE_NM");   // 시·도
            if (!regions.isEmpty() && regions.stream().noneMatch(region::contains)) continue;

            String end = iso(first(r, "RCEPT_ENDDE", "SUBSCRPT_RCEPT_ENDDE", "GNRL_RCEPT_ENDDE"));
            // 이미 접수 마감된 공고는 알릴 이유가 없다. 날짜가 ISO 라 문자열 비교로 충분.
            if (closed(end, today)) continue;

            String no = f(r, "PBLANC_NO"), name = f(r, "HOUSE_NM");
            if (no.isEmpty() || name.isEmpty()) continue;

            String bgn = iso(first(r, "RCEPT_BGNDE", "SUBSCRPT_RCEPT_BGNDE", "GNRL_RCEPT_BGNDE"));
            String win = iso(f(r, "PRZWNER_PRESNATN_DE"));
            String noticeDe = iso(f(r, "RCRIT_PBLANC_DE"));
            List<String> lines = new ArrayList<>();
            Price p = prices.get(no);
            String price = p == null ? "" : p.range();
            String area = p == null ? "" : p.area();
            String units = f(r, "TOT_SUPLY_HSHLDCO");
            if (!units.matches("[1-9]\\d*")) units = "";   // 0 이나 빈 값은 표시하지 않는다
            String addr = f(r, "HSSPLY_ADRES");

            lines.add(name);
            lines.add(join(" · ", region, f(r, "HOUSE_SECD_NM")));
            lines.add(join(" · ", prefix("분양가 ", price),
                    area.isEmpty() ? "" : "전용 " + area,
                    units.isEmpty() ? "" : units + "세대"));
            lines.add(addr);
            lines.add(prefix("공고일 ", noticeDe));
            lines.add(bgn.isEmpty() && end.isEmpty() ? "" : "접수 " + bgn + " ~ " + end);
            lines.add(prefix("당첨발표 ", win));
            lines.add(f(r, "PBLANC_URL"));
            lines.removeIf(String::isBlank);
            out.add(new Item(prefix + no, or(bgn, noticeDe), end,
                    region, gugunOf(addr), addr, price, area, units, String.join("\n", lines)));
        }
        return out;
    }

    static List<Item> fetchSource(String key, Source src, List<String> regions, String today)
            throws IOException {
        // 공고일 최신순으로 내려오므로 앞쪽 200건이면 최근 몇 달을 덮는다.
        String url = src.detail() + "?page=1&perPage=200&serviceKey="
                + URLEncoder.encode(key, StandardCharsets.UTF_8);
        List<Map<String, String>> recs = parseRecords(new String(get(url), StandardCharsets.UTF_8));

        // 분양가는 공고마다 따로 조회해야 한다. 먼저 지역·마감 필터를 통과한 것만 추려서
        // 그만큼만 부른다(200건 전부 부르면 낭비다). 그래서 파싱을 두 번 한다 — 200건짜리
        // 문자열 처리라 비용은 무시할 만하고, 금액을 나중에 문자열에 끼워넣는 것보다 깔끔하다.
        List<Item> first = parseApt(recs, regions, today, src.prefix());
        Map<String, Price> prices = fetchPrices(key, src, first);
        return prices.isEmpty() ? first
                : parseApt(recs, regions, today, src.prefix(), prices);
    }

    /** 공고 하나의 분양가 범위와 그에 해당하는 전용면적 범위. */
    record Price(String range, String area) {}

    /** 주택형 코드 "084.7402A" 의 앞자리가 전용면적(㎡)이다. */
    static final Pattern AREA = Pattern.compile("^(\\d+(?:\\.\\d+)?)");

    /**
     * 공고별 분양가 범위를 구한다. 주택형마다 값이 달라 최저~최고로 묶는다.
     *
     * **소형 타입은 뺀다.** 39㎡·44㎡ 같은 타입이 섞여 있으면 그게 최저가가 되어
     * 실제보다 훨씬 싸 보인다. 충정로역자이르네가 실측으로 전체 10.0~24.6억인데
     * 50㎡ 초과만 보면 18.9~24.6억이다 — "10억부터" 는 40㎡ 얘기였다.
     * 기준은 MIN_AREA(전용 ㎡), 환경변수로 바꿀 수 있다.
     *
     * 다만 **소형만 있는 공고는 그대로 쓴다.** 청계 노르웨이숲(11차)처럼 40㎡ 단일
     * 타입인 건이 있어서, 무조건 빼면 분양가가 통째로 사라진다. 대신 전용면적을
     * 같이 돌려줘서 화면에서 오해가 없게 한다.
     *
     * 한 공고가 실패해도 나머지는 계속한다 — 분양가는 있으면 좋은 정보지 알림의 본질이 아니다.
     */
    static Map<String, Price> fetchPrices(String key, Source src, List<Item> items) {
        Map<String, Price> out = new LinkedHashMap<>();
        for (Item it : items) {
            String no = it.key().substring(src.prefix().length());
            try {
                String url = src.mdl() + "?page=1&perPage=100"
                        + "&serviceKey=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                        + "&" + URLEncoder.encode("cond[PBLANC_NO::EQ]", StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(no, StandardCharsets.UTF_8);

                List<double[]> all = new ArrayList<>();   // {전용면적, 금액}
                for (Map<String, String> r : parseRecords(
                        new String(get(url), StandardCharsets.UTF_8))) {
                    // 임의공급은 "27,600" 처럼 쉼표를 넣어 준다. 다른 소스는 안 넣는다.
                    String v = f(r, "LTTOT_TOP_AMOUNT").replace(",", "");
                    if (!v.matches("\\d+")) continue;
                    long amt = Long.parseLong(v);
                    if (amt <= 0) continue;              // 값이 안 정해진 주택형
                    Matcher m = AREA.matcher(f(r, "HOUSE_TY"));
                    all.add(new double[]{m.find() ? Double.parseDouble(m.group(1)) : 0, amt});
                }
                if (all.isEmpty()) continue;

                List<double[]> use = new ArrayList<>();
                for (double[] x : all) if (x[0] > MIN_AREA) use.add(x);
                if (use.isEmpty()) use = all;            // 소형만 있는 공고는 그대로

                long lo = Long.MAX_VALUE, hi = 0;
                double aLo = Double.MAX_VALUE, aHi = 0;
                for (double[] x : use) {
                    lo = Math.min(lo, (long) x[1]);
                    hi = Math.max(hi, (long) x[1]);
                    if (x[0] > 0) { aLo = Math.min(aLo, x[0]); aHi = Math.max(aHi, x[0]); }
                }
                out.put(no, new Price(priceRange(lo, hi),
                        aHi > 0 ? areaRange(aLo, aHi) : ""));
            } catch (Exception e) {
                System.out.println("분양가 조회 실패(건너뜀) " + no + " - " + e);
            }
        }
        return out;
    }

    /** "60~85㎡". 하나뿐이면 "85㎡". 소수점은 버린다 — 훑어보는 값이다. */
    static String areaRange(double lo, double hi) {
        long a = Math.round(lo), b = Math.round(hi);
        return a == b ? a + "㎡" : a + "~" + b + "㎡";
    }

    /** 만원 단위 금액을 억으로. 124000 -> "12.4억". 훑어보는 용도라 소수 한 자리면 충분하다. */
    static String eok(long manwon) {
        return String.format("%.1f억", manwon / 10000.0);
    }

    /**
     * 최저~최고를 한 줄로. 값은 달라도 억으로 반올림하면 같아지는 경우가 흔해서
     * (44,812 / 44,555 만원 -> 둘 다 4.5억) 표기가 같으면 하나만 쓴다.
     */
    static String priceRange(long min, long max) {
        String lo = eok(min), hi = eok(max);
        return lo.equals(hi) ? lo : lo + " ~ " + hi;
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

    /** 슈퍼그룹 전환 시 텔레그램이 응답에 담아 주는 새 chat_id. */
    static final Pattern MIGRATE = Pattern.compile("\"migrate_to_chat_id\"\\s*:\\s*(-?\\d+)");

    static final String RULE = "─".repeat(16);
    static final int LIMIT = 3800;   // 텔레그램 상한은 4096. 여유를 둔다.

    /**
     * 보낼 메시지를 만든다. 머리말에 건수를 달고, 맨 끝에 웹 주소를 붙인다.
     * 상한을 넘으면 여러 개로 나누고, 나뉜 덩어리에는 머리말을 다시 달아 맥락을 잃지 않게 한다.
     */
    static List<String> compose(List<Item> items, String webUrl, String today) {
        List<String> parts = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        if (!items.isEmpty()) {
            String title = "🏢 아파트 청약 " + items.size() + "건";
            boolean headPending = true;
            for (Item it : items) {
                String body = withBadge(it, today);
                String block = (headPending ? title + "\n" + RULE + "\n\n" : "") + body;
                if (buf.length() > 0 && buf.length() + block.length() + 2 > LIMIT) {
                    parts.add(buf.toString());
                    buf.setLength(0);
                    // 도중에 잘렸으면 새 덩어리에 머리말을 다시 단다.
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
                // 그룹이 슈퍼그룹으로 전환되면 chat_id 가 바뀐다. 인원이 늘 때만이 아니라
                // 공개 링크 설정·관리자 권한 변경 같은 것으로도 전환된다.
                // 텔레그램이 새 ID 를 응답에 담아 주므로 그걸로 한 번 다시 보내고,
                // 무엇을 바꿔야 하는지 로그에 남긴다. 설정을 안 고치면 매번 이 경로를 탄다.
                Matcher m = MIGRATE.matcher(or(e.getMessage(), ""));
                if (m.find()) {
                    String moved = m.group(1);
                    System.out.println("그룹이 슈퍼그룹으로 전환되었습니다. "
                            + "TG_CHAT_ID 를 " + moved + " 로 바꾸세요. 이번에는 새 ID 로 보냅니다.");
                    try {
                        for (String p : parts) send(token, moved, p);
                        continue;
                    } catch (IOException e2) {
                        failed.add(moved + " (전환 후 재시도) → " + e2.getMessage());
                        continue;
                    }
                }
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
                  /* 색은 라이트/다크 두 벌만 두고 나머지는 변수로 참조한다.
                     값을 카드마다 흩어놓으면 나중에 톤을 바꿀 때 다 뒤져야 한다. */
                  :root {
                    color-scheme: light dark;
                    --bg: #fbfbfd;  --fg: #16181d;  --muted: #6b7280;
                    --card: #fff;   --line: #e5e7eb; --shadow: 0 1px 2px #0f172a0d;
                    --urgent: #dc2626; --open: #15803d; --soon: #b45309;
                    --urgent-bg: #fef2f2; --open-bg: #f0fdf4; --soon-bg: #fffbeb;
                  }
                  @media (prefers-color-scheme: dark) {
                    :root {
                      --bg: #0d0f13; --fg: #e8eaed; --muted: #9aa1ad;
                      --card: #16191f; --line: #262b33; --shadow: none;
                      --urgent: #f87171; --open: #4ade80; --soon: #fbbf24;
                      --urgent-bg: #7f1d1d33; --open-bg: #14532d33; --soon-bg: #78350f33;
                    }
                  }
                  * { box-sizing: border-box; }
                  body { margin: 0; background: var(--bg); color: var(--fg);
                         font: 15px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI",
                               "Malgun Gothic", system-ui, sans-serif;
                         -webkit-font-smoothing: antialiased; }
                  .wrap { max-width: 880px; margin: 0 auto; padding: 28px 16px 72px; }
                  h1 { font-size: 1.5rem; font-weight: 700; letter-spacing: -.02em;
                       margin: 0 0 6px; }
                  .sub { color: var(--muted); font-size: .8rem; margin-bottom: 20px; }

                  /* 상단 요약 겸 상태 필터. 오늘 뭘 봐야 하는지가 한 줄로 보이고,
                     눌러서 그 상태만 볼 수 있다. */
                  .stats { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 18px; }
                  .stat { font: inherit; cursor: pointer; color: inherit;
                          background: var(--card); border: 1px solid var(--line);
                          border-radius: 10px; padding: 8px 13px; box-shadow: var(--shadow);
                          transition: border-color .12s, background .12s; }
                  .stat:hover:not(:disabled) { border-color: var(--muted); }
                  .stat:disabled { opacity: .45; cursor: default; }
                  .stat b { font-size: 1.15rem; font-variant-numeric: tabular-nums;
                            margin-right: 5px; }
                  .stat span { color: var(--muted); font-size: .78rem; }
                  .s-urgent b { color: var(--urgent); }
                  .s-open b { color: var(--open); }
                  .s-soon b { color: var(--soon); }
                  /* 선택된 칩. 테두리만 진하게 해서 어느 걸 눌렀는지 바로 보이게 한다. */
                  .stat.on { border-color: currentColor; }
                  .s-urgent.on { border-color: var(--urgent); background: var(--urgent-bg); }
                  .s-open.on   { border-color: var(--open);   background: var(--open-bg); }
                  .s-soon.on   { border-color: var(--soon);   background: var(--soon-bg); }
                  .stat.on:not([class*="s-"]) { border-color: var(--fg); }

                  .filter { position: sticky; top: 0; z-index: 5;
                            display: flex; gap: 8px; align-items: center; flex-wrap: wrap;
                            padding: 10px 0; margin-bottom: 6px;
                            background: var(--bg); border-bottom: 1px solid var(--line); }
                  /* Canvas/CanvasText 는 color-scheme 를 따라가는 시스템 색이다.
                     transparent + inherit 을 쓰면 펼친 목록이 OS 가 그리는 밝은 팝업인데
                     글자만 흰색을 물려받아 다크 모드에서 안 보인다. */
                  .filter select { font: inherit; font-size: .85rem; padding: 7px 10px;
                                   border-radius: 9px; border: 1px solid var(--line);
                                   background: Canvas; color: CanvasText; }
                  .filter select option { background: Canvas; color: CanvasText; }
                  #cnt { color: var(--muted); font-size: .8rem; margin-left: auto; }

                  .list { display: grid; gap: 12px; margin-top: 14px; }
                  @media (min-width: 760px) { .list { grid-template-columns: 1fr 1fr; } }

                  .card { background: var(--card); border: 1px solid var(--line);
                          border-left: 3px solid var(--line);
                          border-radius: 12px; padding: 15px 17px;
                          box-shadow: var(--shadow); }
                  .card.urgent { border-left-color: var(--urgent); }
                  .card.open   { border-left-color: var(--open); }
                  .card.soon   { border-left-color: var(--soon); }

                  .head { display: flex; gap: 10px; align-items: flex-start;
                          margin-bottom: 3px; }
                  .name { font-weight: 650; font-size: 1rem; letter-spacing: -.01em;
                          line-height: 1.35; flex: 1; }
                  .tag { flex: none; font-size: .72rem; font-weight: 700;
                         padding: 3px 9px; border-radius: 999px; white-space: nowrap; }
                  .t-urgent { color: var(--urgent); background: var(--urgent-bg); }
                  .t-open   { color: var(--open);   background: var(--open-bg); }
                  .t-soon   { color: var(--soon);   background: var(--soon-bg); }

                  .where { color: var(--muted); font-size: .8rem; margin-bottom: 10px; }

                  /* 분양가·세대수는 판단에 제일 먼저 보는 값이라 본문과 분리해 크게 둔다. */
                  .key { display: flex; gap: 16px; flex-wrap: wrap;
                         padding: 9px 0 11px; border-top: 1px solid var(--line);
                         border-bottom: 1px solid var(--line); margin-bottom: 10px; }
                  .key div { font-size: .68rem; color: var(--muted);
                             text-transform: uppercase; letter-spacing: .05em; }
                  .key strong { display: block; margin-top: 2px; font-size: .95rem;
                                font-weight: 650; color: var(--fg);
                                font-variant-numeric: tabular-nums; }

                  .rows { font-size: .83rem; color: var(--muted); }
                  .rows p { margin: 0 0 3px; }
                  .rows .k { display: inline-block; min-width: 56px; opacity: .75; }
                  /* 주소가 지도로 연결된다는 걸 보이게 한다. 그냥 글로 보고 지나치기 쉽다. */
                  .map { color: inherit; display: inline-flex; gap: 5px;
                         align-items: baseline; text-decoration: underline dotted;
                         text-decoration-color: var(--muted); text-underline-offset: 3px; }
                  .map .pin { flex: none; font-size: .95em; }
                  .map:hover { color: var(--fg); text-decoration-style: solid;
                               text-decoration-color: currentColor; }
                  .notice { display: inline-block; margin-top: 9px; font-size: .8rem;
                            font-weight: 600; color: var(--fg); text-decoration: none;
                            border: 1px solid var(--line); border-radius: 8px;
                            padding: 5px 11px; }
                  .notice:hover { border-color: var(--muted); }
                  .empty { color: var(--muted); }
                </style>
                """);
        b.append("<div class=\"wrap\">\n<h1>청약 알림</h1>\n<div class=\"sub\">갱신 ")
                .append(htmlEsc(java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
                        .format(java.time.format.DateTimeFormatter
                                .ofPattern("yyyy-MM-dd HH:mm"))))
                .append(" KST · 출처 청약홈 · 접수 마감된 건은 빼고 보여줍니다</div>\n");

        stats(b, items, today);
        section(b, items, today);   // 호출 쪽에서 정렬해서 넘긴다
        b.append("</div>\n");

        // 정적 페이지라 필터는 브라우저에서 보이고 숨기는 방식이다.
        // 시·군·구 목록은 실제로 실린 카드에서 만들어 빈 항목이 안 생기게 한다.
        // 구를 못 읽은 카드(data-gugun="")는 어떤 선택에서도 숨기지 않는다.
        b.append("""
                <script>
                (function () {
                  var cards = [].slice.call(document.querySelectorAll('.card[data-sido]'));
                  var box = document.querySelector('.filter');
                  if (!box || !cards.length) { if (box) box.style.display = 'none'; return; }
                  var sido = document.getElementById('sido'),
                      gugun = document.getElementById('gugun'),
                      cnt = document.getElementById('cnt');

                  function options(list, label) {
                    var seen = {}, out = ['<option value="">' + label + '</option>'];
                    list.forEach(function (v) {
                      if (v && !seen[v]) { seen[v] = 1; out.push('<option>' + v + '</option>'); }
                    });
                    return out.join('');
                  }
                  // 시·도가 바뀌면 시·군·구는 항상 초기화한다. "남아 있으면 유지"로 두면
                  // 규칙이 둘이 되어 예측이 안 되고, 무엇보다 시·도 없이 구만 남는 상태가
                  // 생긴다. 중구는 서울과 인천에 모두 있어서 그러면 두 지역이 섞여 나온다.
                  // 시·도가 전체일 때는 상자 자체를 숨긴다. 고를 수 없으면 헷갈릴 일도 없다.
                  function fillGugun() {
                    var s = sido.value;
                    gugun.innerHTML = options(cards.filter(function (c) {
                      return !s || c.dataset.sido === s;
                    }).map(function (c) { return c.dataset.gugun; }), '시·군·구 전체');
                    gugun.value = '';
                    gugun.style.display = s ? '' : 'none';
                  }
                  // 지역 필터와 상태 필터는 AND 로 걸린다.
                  // 상단 칩의 숫자는 "지금 지역 선택 안에서" 몇 건인지를 보여준다.
                  // 전체 건수를 그대로 두면 서울만 골랐는데 칩은 19건이라 앞뒤가 안 맞는다.
                  var chips = [].slice.call(document.querySelectorAll('.stat'));
                  var status = '';

                  function apply() {
                    var s = sido.value, g = gugun.value, n = 0;
                    var count = { '': 0, urgent: 0, open: 0, soon: 0 };
                    cards.forEach(function (c) {
                      var inRegion = (!s || c.dataset.sido === s)
                                  && (!g || !c.dataset.gugun || c.dataset.gugun === g);
                      if (inRegion) { count['']++; count[c.dataset.status]++; }
                      var ok = inRegion && (!status || c.dataset.status === status);
                      c.style.display = ok ? '' : 'none';
                      if (ok) n++;
                    });
                    chips.forEach(function (chip) {
                      var k = chip.dataset.status;
                      chip.querySelector('b').textContent = count[k];
                      chip.classList.toggle('on', k === status);
                      // 0건인 상태는 눌러도 빈 화면만 나오므로 막는다. "전체" 는 항상 열어둔다.
                      chip.disabled = k !== '' && count[k] === 0;
                    });
                    cnt.textContent = n + '건';
                  }

                  chips.forEach(function (chip) {
                    chip.onclick = function () {
                      // 같은 칩을 다시 누르면 해제된다. 전체로 돌아가는 길을 하나 더 둔다.
                      status = (status === chip.dataset.status) ? '' : chip.dataset.status;
                      apply();
                    };
                  });

                  sido.innerHTML = options(cards.map(function (c) { return c.dataset.sido; }),
                                           '시·도 전체');
                  sido.onchange = function () { fillGugun(); apply(); };
                  gugun.onchange = apply;
                  fillGugun();
                  apply();
                })();
                </script>
                """);

        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(out, b.toString(), StandardCharsets.UTF_8);
        System.out.println("페이지 생성: " + out.toAbsolutePath().normalize());
    }

    /** 상단 요약. 오늘 뭘 봐야 하는지가 한 줄로 보이게 한다. */
    static void stats(StringBuilder b, List<Item> items, String today) {
        int urgent = 0, open = 0, soon = 0;
        for (Item it : items) {
            switch (badge(it, today)[1]) {
                case "t-urgent" -> urgent++;
                case "t-open" -> open++;
                default -> soon++;
            }
        }
        b.append("<div class=\"stats\">")
                .append(stat("", "", items.size(), "전체"))
                .append(stat("s-urgent", "urgent", urgent, "마감 임박"))
                .append(stat("s-open", "open", open, "접수중"))
                .append(stat("s-soon", "soon", soon, "예정"))
                .append("</div>\n");
    }

    /** status 가 빈 값이면 "전체" 칩이다. 카드의 data-status 와 짝을 맞춘다. */
    static String stat(String cls, String status, int n, String label) {
        return "<button type=\"button\" class=\"stat " + cls + "\" data-status=\""
                + status + "\"><b>" + n + "</b><span>" + label + "</span></button>";
    }

    static void section(StringBuilder b, List<Item> all, String today) {
        b.append("""
                <div class="filter">
                  <select id="sido"><option value="">시·도 전체</option></select>
                  <select id="gugun"><option value="">시·군·구 전체</option></select>
                  <span id="cnt"></span>
                </div>
                """);
        if (all.isEmpty()) {
            b.append("<p class=\"empty\">수집된 건이 없습니다.</p>\n");
            return;
        }
        b.append("<div class=\"list\">\n");
        for (Item it : all) {
            String[] lines = it.text().split("\n");
            String[] s = badge(it, today);   // [카드 클래스, 태그 클래스, 문구, 이모지]
            String cls = s[0].isEmpty() ? "soon" : s[0];
            // data-* 는 필터가 읽는다. 값이 비어 있어도 속성 자체는 항상 남긴다.
            b.append("<div class=\"card ").append(cls).append("\"")
                    .append(" data-status=\"").append(cls).append("\"")
                    .append(" data-sido=\"").append(htmlEsc(it.sido()))
                    .append("\" data-gugun=\"").append(htmlEsc(it.gugun())).append("\">")
                    .append("<div class=\"head\"><div class=\"name\">")
                    .append(htmlEsc(lines[0])).append("</div>")
                    .append("<span class=\"tag ").append(s[1]).append("\">")
                    .append(s[2]).append("</span></div>");

            // 둘째 줄은 "지역 · 구분". 구를 알면 시·도와 함께 보여준다.
            b.append("<div class=\"where\">")
                    .append(htmlEsc(lines.length > 1 ? withGugun(lines[1], it) : ""))
                    .append("</div>");

            if (!it.price().isEmpty() || !it.units().isEmpty()) {
                b.append("<div class=\"key\">");
                if (!it.price().isEmpty()) {
                    b.append("<div>분양가<strong>").append(htmlEsc(it.price()))
                            .append("</strong></div>");
                }
                // 분양가가 어느 면적 기준인지 같이 보여준다. 소형을 뺐다는 게 여기서 드러난다.
                if (!it.area().isEmpty()) {
                    b.append("<div>전용면적<strong>").append(htmlEsc(it.area()))
                            .append("</strong></div>");
                }
                if (!it.units().isEmpty()) {
                    b.append("<div>공급규모<strong>").append(htmlEsc(it.units()))
                            .append("세대</strong></div>");
                }
                b.append("</div>");
            }

            b.append("<div class=\"rows\">");
            String notice = "";
            for (int i = 2; i < lines.length; i++) {
                String l = lines[i];
                if (l.startsWith("http")) { notice = l; continue; }
                if (l.startsWith("분양가") || l.contains("세대")) continue;   // 위에서 이미 크게 보여줬다
                if (!it.addr().isEmpty() && l.equals(it.addr())) {
                    // 주소는 지도 검색으로 연결한다. 좌표가 API 에 없어 지도를 직접 그리려면
                    // 지오코딩 키가 하나 더 필요한데, 링크면 그 비용 없이 목적을 채운다.
                    b.append("<p><a class=\"map\" href=\"").append(htmlEsc(mapUrl(l)))
                            .append("\" target=\"_blank\" rel=\"noopener\"")
                            .append(" title=\"네이버 지도에서 보기\">")
                            .append("<span class=\"pin\" aria-hidden=\"true\">📍</span>")
                            .append("<span>").append(htmlEsc(l)).append("</span></a></p>");
                } else {
                    b.append("<p>").append(labeled(l)).append("</p>");
                }
            }
            b.append("</div>");
            if (!notice.isEmpty()) {
                b.append("<a class=\"notice\" href=\"").append(htmlEsc(notice))
                        .append("\" target=\"_blank\" rel=\"noopener\">공고 보기</a>");
            }
            b.append("</div>\n");
        }
        b.append("</div>\n");
    }

    /** "접수 2026-08-18 ~ 2026-08-21" -> 앞머리를 흐리게 해서 값이 먼저 읽히게 한다. */
    static String labeled(String line) {
        int i = line.indexOf(' ');
        if (i <= 0) return htmlEsc(line);
        return "<span class=\"k\">" + htmlEsc(line.substring(0, i)) + "</span>"
                + htmlEsc(line.substring(i + 1));
    }

    /** "서울 · APT" 에 구를 끼워 "서울 영등포구 · APT" 로. 구를 모르면 그대로 둔다. */
    static String withGugun(String where, Item it) {
        if (it.gugun().isEmpty() || !where.startsWith(it.sido())) return where;
        return it.sido() + " " + it.gugun() + where.substring(it.sido().length());
    }

    /**
     * 상태 배지. [카드 클래스, 태그 클래스, 문구, 이모지].
     * 앞의 둘은 웹 페이지가, 뒤의 둘은 텔레그램 메시지가 쓴다.
     * 마감된 건은 수집 단계에서 이미 빠지므로 여기서는 다루지 않는다.
     */
    static String[] badge(Item it, String today) {
        // 상태 판정은 stateOf 하나로 모은다. 배지와 알림 기준이 갈리면 화면과 알림이 어긋난다.
        return switch (stateOf(it, today)) {
            case "soon" -> new String[]{"", "t-soon", "예정", "🟠"};
            case "d0" -> new String[]{"urgent", "t-urgent", "오늘 마감", "🔴"};
            case "d1" -> new String[]{"urgent", "t-urgent", "내일 마감", "🔴"};
            // 모레는 아직 급하지 않으니 색은 접수중과 같이 두고 문구로만 알린다.
            case "d2" -> new String[]{"open", "t-open", "모레 마감", "🟢"};
            default -> new String[]{"open", "t-open", "접수중", "🟢"};
        };
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

        String where = regions.isEmpty() ? "전국" : String.join(",", regions);
        if (isBlank(aptKey)) {
            System.out.println("APPLYHOME_KEY 없음 - 수집을 건너뜁니다");
        } else {
            // 한 소스가 죽어도 다른 소스는 알린다.
            for (Source src : SOURCES) {
                try {
                    List<Item> got = fetchSource(aptKey, src, regions, today);
                    System.out.println(src.label() + ": " + got.size() + "건 수집 (" + where + ")");
                    items.addAll(got);
                } catch (Exception e) {
                    System.out.println(src.label() + ": 수집 실패 - " + e);
                }
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
            for (Item i : items) all.add(notifyKey(i, today));
            saveSeen(all);
            System.out.println("첫 실행: " + items.size()
                    + "건을 기준으로 등록했습니다. 다음 실행부터 신규 건만 알립니다.");
            System.out.println("중복 방지 파일: " + seenFile());
            return;
        }

        // 상태 없는 옛 키가 남아 있으면 그 공고의 지금 상태는 이미 알린 것으로 올려둔다.
        // 안 하면 키 형식이 바뀌는 순간 전부 신규로 잡혀 한 번 도배된다.
        for (Item i : items) {
            if (seen.remove(i.key())) seen.add(notifyKey(i, today));
        }

        List<Item> fresh = new ArrayList<>();
        for (Item i : items) if (!seen.contains(notifyKey(i, today))) fresh.add(i);
        if (fresh.isEmpty()) { System.out.println("신규 없음"); return; }

        String webUrl = or(System.getenv("WEB_URL"), "");
        if (dry) {
            // 실제로 나갈 메시지 그대로 찍는다.
            System.out.println(String.join("\n\n──────── 다음 메시지 ────────\n\n",
                    compose(fresh, webUrl, today)));
        } else {
            sendAll(token, chats, fresh, webUrl, today);
            System.out.println(fresh.size() + "건 전송 (수신자 " + chats.size() + "명)");
            for (Item i : fresh) seen.add(notifyKey(i, today));
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

    /** 후보 필드명을 순서대로 보고 처음 값이 있는 것. 엔드포인트마다 이름이 달라서 필요하다. */
    static String first(Map<String, String> m, String... keys) {
        for (String k : keys) {
            String v = f(m, k);
            if (!v.isEmpty()) return v;
        }
        return "";
    }
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

        // 소스마다 날짜 형식이 다르다. 안 맞추면 마감 판정이 통째로 무력화된다.
        check(iso("20260813").equals("2026-08-13"), "8자리 -> ISO");
        check(iso("2026-08-13").equals("2026-08-13") && iso("").isEmpty(), "이미 ISO 면 그대로");
        check(closed(iso("20260811"), today), "변환 후에는 마감으로 잡힌다");
        check(!closed("20260811", today), "변환 안 하면 마감이 안 잡힌다 — 이게 실제 버그였다");

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

        List<Item> seoul = parseApt(recs, List.of("서울"), today, "apt:");
        check(seoul.size() == 1 && seoul.get(0).key().equals("apt:1"), "지역+마감 필터");
        check(parseApt(recs, List.of(), today, "apt:").size() == 2, "필터 없어도 마감건/번호없는건 제외");
        check(parseApt(recs, List.of("서울", "경기", "인천"), today, "apt:").size() == 1, "여러 지역 OR 매칭");
        check(Arrays.asList(DEFAULT_REGIONS.split(",")).equals(List.of("서울", "경기", "인천")),
                "기본 지역은 수도권 3곳");

        // 분양가 (주택형별 상세에서 온다)
        check(eok(124000).equals("12.4억") && eok(79831).equals("8.0억"), "만원 -> 억 변환");
        check(priceRange(44812, 44555).equals("4.5억"), "반올림하면 같은 값은 하나만");
        check(priceRange(73700, 299900).equals("7.4억 ~ 30.0억"), "다르면 범위로");
        List<Item> priced = parseApt(recs, List.of("서울"), today, "apt:",
                Map.of("1", new Price("11.8억 ~ 14.5억", "60~85㎡")));
        // 테스트 레코드 1번은 TOT_SUPLY_HSHLDCO=594 라 전용면적·세대수가 같은 줄에 붙는다
        check(priced.get(0).text().contains("\n분양가 11.8억 ~ 14.5억 · 전용 60~85㎡ · 594세대\n"),
                "분양가+전용면적+세대수 줄");
        check(priced.get(0).price().equals("11.8억 ~ 14.5억")
                && priced.get(0).area().equals("60~85㎡")
                && priced.get(0).units().equals("594"), "셋 다 필드로도 들고 있다");

        // 소형 타입 제외 기준과 면적 표기
        check(MIN_AREA == 50, "기본 기준은 전용 50㎡");
        check(parseArea("60", 50) == 60 && parseArea(null, 50) == 50
                && parseArea("이상한값", 50) == 50, "MIN_AREA 파싱과 기본값");
        check(areaRange(59.97, 84.98).equals("60~85㎡"), "면적 범위");
        check(areaRange(39.7, 39.7).equals("40㎡"), "하나뿐이면 하나만");
        check(!seoul.get(0).text().contains("분양가"), "금액 없으면 그 줄에 분양가가 안 나온다");
        check(seoul.get(0).text().contains("594세대"), "금액이 없어도 세대수는 나온다");
        check(parseApt(recs, List.of(), today, "apt:").get(1).units().isEmpty(),
                "세대수 필드가 없으면 빈 값");

        check(esc("따\"옴\\표\n").equals("따\\\"옴\\\\표\\n"), "JSON 이스케이프");
        check(unescape("\\uD55C\\uAE00 \\\"인용\\\"").equals("한글 \"인용\""), "JSON 이스케이프 해제");

        // 정렬 키
        check(seoul.get(0).start().equals("2026-08-01"), "접수시작일 없으면 공고일로 대체");
        check(parseApt(recs, List.of(), today, "apt:").get(1).start().equals("2026-08-30"), "접수시작일 우선");

        // 상태 판정과 정렬: 접수 중(임박한 마감 순) 먼저, 그다음 예정(빨리 시작하는 순)
        Item urgent = new Item("apt:u", "2026-08-01", "2026-08-12", "서울", "마포구", "", "", "", "", "오늘마감");
        Item tmr    = new Item("apt:t", "2026-08-01", "2026-08-13", "서울", "마포구", "", "", "", "", "내일마감");
        Item open   = new Item("apt:o", "2026-08-01", "2026-08-20", "경기", "성남시", "", "", "", "", "접수중");
        Item soon1  = new Item("apt:s1", "2026-08-18", "2026-08-21", "경기", "", "", "", "", "", "곧시작");
        Item soon2  = new Item("apt:s2", "2026-08-31", "2026-09-08", "인천", "연수구", "", "", "", "", "나중시작");
        check(badge(urgent, today)[2].equals("오늘 마감"), "오늘 마감 배지");
        check(badge(tmr, today)[2].equals("내일 마감"), "내일 마감 배지");
        check(badge(open, today)[2].equals("접수중"), "접수중 배지");
        check(badge(soon1, today)[2].equals("예정"), "예정 배지");
        check(badge(urgent, today)[0].equals("urgent") && badge(open, today)[0].equals("open"),
                "카드 강조 클래스");

        // 상태 전이마다 다시 알린다. 공고 번호만으로 판정하면 평생 한 번만 알리게 된다.
        check(stateOf(soon1, today).equals("soon"), "접수 전은 soon");
        check(stateOf(open, today).equals("open"), "접수 기간 안은 open");
        check(stateOf(tmr, today).equals("d1"), "내일 마감은 d1");
        check(stateOf(urgent, today).equals("d0"), "오늘 마감은 d0");
        check(stateOf(new Item("apt:x", "2026-08-01", "2026-08-14", "", "", "", "", "", "", ""),
                today).equals("d2"), "모레 마감은 d2");
        check(stateOf(new Item("apt:y", "2026-08-01", "2026-08-15", "", "", "", "", "", "", ""),
                today).equals("open"), "사흘 뒤부터는 그냥 접수중");
        check(notifyKey(urgent, today).equals("apt:u:d0"), "알림 키에 상태가 붙는다");

        // 긴 접수(08-18~08-26, 9일)를 날짜만 바꿔가며 보면 마지막 사흘이 연달아 달라진다
        Item run = new Item("apt:r", "2026-08-18", "2026-08-26", "서울", "중구", "", "", "", "", "x");
        List<String> keys = new ArrayList<>();
        for (String d : List.of("2026-08-12", "2026-08-18", "2026-08-21", "2026-08-23",
                "2026-08-24", "2026-08-25", "2026-08-26")) {
            String k = notifyKey(run, d);
            if (keys.isEmpty() || !keys.get(keys.size() - 1).equals(k)) keys.add(k);
        }
        check(keys.equals(List.of("apt:r:soon", "apt:r:open",
                        "apt:r:d2", "apt:r:d1", "apt:r:d0")),
                "예정 -> 접수중 -> 모레 -> 내일 -> 오늘 마감 (실제=" + keys + ")");
        check(notifyKey(run, "2026-08-18").equals(notifyKey(run, "2026-08-21")),
                "같은 상태가 이어지면 다시 안 알린다");

        List<Item> order = sortForDisplay(List.of(soon2, open, soon1, urgent, tmr), today);
        List<String> sorted = new ArrayList<>();
        for (Item i : order) sorted.add(i.key());
        check(sorted.equals(List.of("apt:u", "apt:t", "apt:o", "apt:s1", "apt:s2")),
                "접수중(마감임박순) -> 예정(시작순) 정렬, 실제=" + sorted);
        check(htmlEsc("<a href=\"x\">&</a>")
                .equals("&lt;a href=&quot;x&quot;&gt;&amp;&lt;/a&gt;"), "HTML 이스케이프");

        // 지도 검색어 정리
        check(mapQuery("서울특별시 송파구 거여동 181, 202번지 일원")
                .equals("서울특별시 송파구 거여동 181번지"), "복수 번지 -> 첫 번째, 일원 제거");
        check(mapQuery("인천광역시 검단구 검단신도시 AB23BL(인천광역시 검단구 마전동 산175-7번지 일원)")
                .equals("인천광역시 검단구 마전동 산175-7번지"), "괄호 안에 번지가 있으면 그쪽");
        check(mapQuery("경기도 김포시 고촌읍 향산리 588-45번지 일원")
                .equals("경기도 김포시 고촌읍 향산리 588-45번지"), "일원 제거");
        check(mapQuery("서울특별시 노원구 월계동 487-17번지 일대")
                .equals("서울특별시 노원구 월계동 487-17번지"), "일대 제거");
        // 번지·도로명이 없으면 행정구역까지만. 네이버에 지구·단지명이 없는 경우가 많아서다.
        check(mapQuery("경기도 용인시 처인구 원삼면 용인 반도체 클러스터 일반산업단지 D1-1BL")
                .equals("경기도 용인시 처인구 원삼면"), "번지 없으면 행정구역까지만");
        check(mapQuery("경기도 시흥시 거모동, 군자동 시흥거모 공공주택지구 내 A-5블록")
                .equals("경기도 시흥시 거모동"), "동은 최말단이라 첫 동에서 끊는다");
        check(adminOnly("D1-1BL 뭐시기").isEmpty(), "행정구역으로 시작하지 않으면 빈 값");
        check(mapQuery("경기도 성남시 수정구 신흥동 81-8")
                .equals("경기도 성남시 수정구 신흥동 81-8"), "지번은 블록 코드로 오인하지 않는다");

        // 네이버는 경로에 검색어가 들어가므로 공백이 "+" 가 아니라 "%20" 이어야 한다
        String u = mapUrl("서울특별시 중구 중림동 157-2번지");
        check(u.startsWith("https://map.naver.com/p/search/"), "네이버 지도 주소");
        check(!u.contains("+"), "경로에 + 가 남으면 안 된다 (실제=" + u + ")");
        check(u.contains("%20"), "공백은 %20");
        check(mapQuery("경기도 오산시 오산세교2지구 A-13블록 호반써밋(경기도 오산시 초평중앙로 65)")
                .equals("경기도 오산시 초평중앙로 65"), "괄호 안이 도로명이어도 그쪽");
        check(mapQuery("경기도 광주시 초월읍 도곡길 27(쌍동리 402)")
                .equals("경기도 광주시 초월읍 도곡길 27"), "바깥이 도로명이면 괄호를 뗀다");

        // 페이지는 소스(키 접두어)와 무관하게 전부 싣는다.
        // 한때 "apt:" 로만 걸러서 무순위 건이 페이지에서만 통째로 빠진 적이 있다.
        StringBuilder page = new StringBuilder();
        section(page, List.of(
                new Item("apt:1", "2026-08-01", "2026-08-20", "서울", "마포구", "", "", "", "", "분양건\n서울"),
                new Item("rem:2", "2026-08-01", "2026-08-20", "인천", "검단구", "", "", "", "", "무순위건\n인천")), today);
        check(page.indexOf("분양건") > 0 && page.indexOf("무순위건") > 0, "두 소스 모두 페이지에 실림");
        check(page.indexOf("data-sido=\"인천\" data-gugun=\"검단구\"") > 0, "무순위에도 필터 속성");
        check(argValue(List.of("--html", "docs/index.html"), "--html").equals("docs/index.html")
                && argValue(List.of("--html"), "--html") == null, "인자 값 파싱");

        // 수신자 여러 명
        check(splitCsv(" 111 ,222,, 333 ").equals(List.of("111", "222", "333")), "쉼표 목록 파싱");

        // 슈퍼그룹 전환 응답에서 새 chat_id 를 뽑아낸다 (실제로 겪은 오류 본문)
        Matcher mg = MIGRATE.matcher("텔레그램 전송 실패 - HTTP 400 - {\"ok\":false,\"error_code\":400,"
                + "\"description\":\"Bad Request: group chat was upgraded to a supergroup chat\","
                + "\"parameters\":{\"migrate_to_chat_id\":-1004320070051}}");
        check(mg.find() && mg.group(1).equals("-1004320070051"), "전환된 새 chat_id 추출");
        check(!MIGRATE.matcher("HTTP 403 - bot was blocked").find(), "다른 오류엔 안 걸린다");
        check(splitCsv(null).isEmpty() && splitCsv("  ").isEmpty(), "빈 목록");
        // 메시지 구성: 머리말 + 건수 + 하단 웹 주소
        List<Item> mixed = List.of(
                new Item("apt:1", "2026-08-01", "2026-08-20", "서울", "마포구", "", "", "", "", "가나아파트\n서울"),
                new Item("apt:2", "2026-08-02", "2026-08-21", "경기", "성남시", "", "", "", "", "다라아파트\n경기"));
        List<String> msg = compose(mixed, "https://example.com/", today);
        check(msg.size() == 1, "짧으면 한 통");
        String m = msg.get(0);
        check(m.startsWith("🏢 아파트 청약 2건"), "머리말과 건수");
        check(m.endsWith("웹 브라우저에서 보기\nhttps://example.com/"), "맨 끝에 웹 주소");
        check(!compose(mixed, "", today).get(0).contains("웹 브라우저에서 보기"),
                "WEB_URL 없으면 링크 없음");
        check(compose(List.of(), "https://example.com/", today).isEmpty(), "보낼 게 없으면 빈 목록");

        // 메시지에도 상태 배지가 붙는다
        check(withBadge(urgent, today).startsWith("🔴 오늘 마감 · 오늘마감"), "메시지 배지 - 오늘 마감");
        check(withBadge(open, today).startsWith("🟢 접수중 · 접수중"), "메시지 배지 - 접수중");
        check(withBadge(soon1, today).startsWith("🟠 예정 · 곧시작"), "메시지 배지 - 예정");
        check(withBadge(new Item("apt:x", "2026-08-01", "2026-08-20", "서울", "중구", "", "", "", "", "이름\n둘째줄\n셋째줄"), today)
                .endsWith("\n둘째줄\n셋째줄"), "첫 줄에만 붙고 나머지는 그대로");
        check(m.contains("🟢 접수중 · 가나아파트"), "실제 메시지에 배지 반영");

        // 시·군·구 추출 (API 에 필드가 없어 주소를 자른다)
        check(gugunOf("서울특별시 영등포구 신길동 413-8번지 일원").equals("영등포구"), "서울 구");
        check(gugunOf("경기도 성남시 수정구 신흥동 81-8").equals("성남시"), "특례시는 시까지만");
        check(gugunOf("인천광역시 연수구 송도동").equals("연수구"), "인천 구");
        check(gugunOf("세종특별자치시 다솜동 5204-1번지").isEmpty(), "시·군·구 단계가 없으면 빈 값");
        check(gugunOf("").isEmpty() && gugunOf("주소미상").isEmpty(), "형식 어긋나면 빈 값");

        // 상한 초과 시 분할 + 잘린 덩어리에 머리말 재부착
        List<Item> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(new Item("apt:k" + i, "2026-01-01", "2026-12-31", "서울", "중구", "", "", "", "", "가".repeat(60)));
        }
        List<String> parts = compose(many, "https://example.com/", today);
        check(parts.size() > 1, "4096자 넘으면 나눠 보낸다");
        for (String p : parts) check(p.length() <= LIMIT, "덩어리 크기 제한 (" + p.length() + ")");
        check(parts.get(1).startsWith("🏢 아파트 청약 200건 (이어서)"), "이어지는 덩어리에 머리말 재부착");
        check(parts.get(parts.size() - 1).endsWith("https://example.com/"), "웹 주소는 마지막에 한 번");

        System.out.println("selftest OK");
    }
}
