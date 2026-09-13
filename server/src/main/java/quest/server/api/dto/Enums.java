package quest.server.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Wire enums. Values must match the Kotlin DTOs in shared-api exactly. */
public final class Enums {
    private Enums() {}

    public enum Subject {
        MATH("math"), ENGLISH("english");
        private final String wire;
        Subject(String wire) { this.wire = wire; }
        @JsonValue public String wire() { return wire; }
        @JsonCreator public static Subject from(String s) { for (var v : values()) if (v.wire.equals(s)) return v; throw new IllegalArgumentException("subject " + s); }
    }

    public enum Mode {
        NORMAL("normal"), AGAIN("again"), HARDER("harder"), EASIER("easier");
        private final String wire;
        Mode(String wire) { this.wire = wire; }
        @JsonValue public String wire() { return wire; }
        @JsonCreator public static Mode from(String s) { for (var v : values()) if (v.wire.equals(s)) return v; throw new IllegalArgumentException("mode " + s); }
    }

    public enum Status {
        UPLOADING("uploading"), READING("reading"), NEEDS_CONFIRMATION("needs_confirmation"),
        GENERATING("generating"), READY("ready"), ERROR("error");
        private final String wire;
        Status(String wire) { this.wire = wire; }
        @JsonValue public String wire() { return wire; }
        @JsonCreator public static Status from(String s) { for (var v : values()) if (v.wire.equals(s)) return v; throw new IllegalArgumentException("status " + s); }
        public boolean isTerminal() { return this == NEEDS_CONFIRMATION || this == READY || this == ERROR; }
    }
}
