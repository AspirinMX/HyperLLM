package app.pagefault.hyperllm.admin.alert;

import app.pagefault.hyperllm.admin.config.AdminProperties;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;

import java.util.Properties;

/** Sends alert mail through the configured SMTP adapter; absent configuration is a permanent failure. */
@Component
public class EmailSender {

    private final AdminProperties properties;

    public EmailSender(AdminProperties properties) {
        this.properties = properties;
    }

    public void send(String target, String subject, String body) {
        AdminProperties.Alerts alerts = properties.getAlerts();
        if (alerts.getSmtpHost() == null || alerts.getSmtpHost().isBlank()) {
            throw new NotificationException("SMTP_NOT_CONFIGURED", "No SMTP host is configured", true);
        }
        try {
            Properties config = new Properties();
            config.put("mail.smtp.host", alerts.getSmtpHost());
            config.put("mail.smtp.port", Integer.toString(alerts.getSmtpPort()));
            config.put("mail.smtp.starttls.enable", Boolean.toString(alerts.isSmtpStartTls()));
            config.put("mail.smtp.connectiontimeout", "5000");
            config.put("mail.smtp.timeout", "10000");
            boolean auth = alerts.getSmtpUsername() != null && !alerts.getSmtpUsername().isBlank();
            config.put("mail.smtp.auth", Boolean.toString(auth));
            Session session = Session.getInstance(config, auth ? new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(alerts.getSmtpUsername(), alerts.getSmtpPassword());
                }
            } : null);
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(alerts.getMailFrom()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(target, false));
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");
            Transport.send(message);
        } catch (Exception ex) {
            throw new NotificationException("SMTP_SEND", "Cannot send the alert mail", false, ex);
        }
    }
}
