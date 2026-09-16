package quest.server.mail;

import org.springframework.stereotype.Service;
import quest.server.config.QuestProperties;

/**
 * The two transactional emails phase 1 sends: the invite link and the password-reset link. Subjects carry the
 * platform name from {@link PlatformName}; links are built from `quest.dashboard-url` (DASHBOARD_URL) and point
 * at the Angular dashboard's `/dashboard/` routes (D10).
 */
@Service
public class DashboardMails {
    private final Mailer mailer; private final PlatformName platform; private final String dashboardUrl;

    public DashboardMails(Mailer mailer, PlatformName platform, QuestProperties props) {
        this.mailer = mailer; this.platform = platform;
        // DASHBOARD_URL is the origin the dashboard is served from; the Angular dashboard mounts at `/dashboard/` (D10),
        // so the links below carry that prefix. `webAdmin`'s `/panel/` keeps serving the old bundle until P3.6 retires it.
        String configured = props.dashboardUrl();
        String base = configured == null || configured.isBlank() ? (props.publicUrl() == null ? "" : props.publicUrl()) : configured;
        this.dashboardUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public String acceptInviteLink(String token) { return dashboardUrl + "/dashboard/accept-invite?token=" + token; }
    public String resetPasswordLink(String token) { return dashboardUrl + "/dashboard/reset-password?token=" + token; }

    public void sendInvite(String to, String schoolName, String role, String token) {
        String link = acceptInviteLink(token);
        String where = schoolName == null || schoolName.isBlank() ? platform.get() : schoolName;
        String subject = platform.get() + " — you have been invited to " + where;
        String text = "You have been invited to " + where + " as " + role.toLowerCase() + ".\n\n"
                + "Set your password here (the link works once and expires in 7 days):\n" + link + "\n";
        mailer.send(to, subject, text, html("You have been invited to " + escape(where), "Set your password", link, "The link works once and expires in 7 days."));
    }

    public void sendPasswordReset(String to, String token) {
        String link = resetPasswordLink(token);
        String subject = platform.get() + " — reset your password";
        String text = "Somebody asked to reset your " + platform.get() + " password.\n\n"
                + "Choose a new one here (the link works once and expires in an hour):\n" + link + "\n\n"
                + "If it was not you, ignore this email: nothing has changed.\n";
        mailer.send(to, subject, text, html("Reset your password", "Choose a new password", link, "The link works once and expires in an hour. If it was not you, ignore this email."));
    }

    private String html(String heading, String action, String link, String note) {
        return "<div style=\"font-family:Archivo,Helvetica,Arial,sans-serif;color:#1a1a1a\">"
                + "<h1 style=\"font-size:20px;margin:0 0 16px\">" + escape(heading) + "</h1>"
                + "<p style=\"margin:0 0 20px\"><a href=\"" + escape(link) + "\" style=\"display:inline-block;padding:12px 20px;background:#d4261a;color:#fff;text-decoration:none\">" + escape(action) + "</a></p>"
                + "<p style=\"font-size:13px;color:#666;margin:0\">" + escape(note) + "</p></div>";
    }

    private static String escape(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
