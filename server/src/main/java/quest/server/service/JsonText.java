package quest.server.service;

/** Models occasionally wrap JSON in ``` fences despite instructions; strip them before validation. */
final class JsonText {
    private JsonText() {}

    static String stripFences(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            s = firstNewline >= 0 ? s.substring(firstNewline + 1) : s.substring(3);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : s.strip();
    }
}
