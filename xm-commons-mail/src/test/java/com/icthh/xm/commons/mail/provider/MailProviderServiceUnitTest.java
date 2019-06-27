package com.icthh.xm.commons.mail.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

public class MailProviderServiceUnitTest {

    public static final String TENANT = "TEST";

    private static final String MAIL_CONFIG = "mail-provider:\n"
                                              + "      host: localhost\n"
                                              + "      port: 25\n"
                                              + "      protocol: smtp\n"
                                              + "      username: mailuser\n"
                                              + "      password: mailpass\n"
                                              + "      properties:\n"
                                              + "          mail.smtp.starttls.enable: true\n"
                                              + "          ssl.trust: true\n"
                                              + "          mail.imap.ssl.enable: true\n";

    private MailProviderService providerService;

    private final JavaMailSender defaultMailSender = new JavaMailSenderImpl();

    @Before
    public void before() {
        providerService = new MailProviderService(defaultMailSender);
    }

    @Test
    public void testMailProviderAddedOnInit() {

        assertFalse(providerService.isTenantMailSenderExists(TENANT));

        providerService.onInit("/config/tenants/TEST/mail-provider.yml", MAIL_CONFIG);

        assertTrue(providerService.isTenantMailSenderExists(TENANT));

    }

    @Test
    public void testMailProviderReturnDefault() {

        assertFalse(providerService.isTenantMailSenderExists(TENANT));

        assertEquals(defaultMailSender, providerService.getJavaMailSender(TENANT));

    }

    @Test
    public void testNoExceptionOnInvalidConfig() {

        assertFalse(providerService.isTenantMailSenderExists(TENANT));

        providerService.onInit("/config/tenants/TEST/mail-provider.yml", "sdfsd --- fsdfe");

        assertEquals(defaultMailSender, providerService.getJavaMailSender(TENANT));

    }

    @Test
    public void testMailProviderAddedOnRefresh() {

        assertFalse(providerService.isTenantMailSenderExists(TENANT));

        providerService.onRefresh("/config/tenants/TEST/mail-provider.yml", MAIL_CONFIG);

        assertTrue(providerService.isTenantMailSenderExists(TENANT));

        JavaMailSenderImpl javaMailSender = (JavaMailSenderImpl) providerService.getJavaMailSender(TENANT);

        assertNotNull(javaMailSender);

        assertEquals("localhost", javaMailSender.getHost());
        assertEquals(25, javaMailSender.getPort());
        assertEquals("smtp", javaMailSender.getProtocol());
        assertEquals("mailuser", javaMailSender.getUsername());
        assertEquals("mailpass", javaMailSender.getPassword());

        Properties properties = javaMailSender.getJavaMailProperties();

        assertEquals(3, properties.size());

        assertEquals("true", properties.getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", properties.getProperty("ssl.trust"));
        assertEquals("true", properties.getProperty("mail.imap.ssl.enable"));

    }

    @Test
    public void testMailProviderRemoved() {

        assertFalse(providerService.isTenantMailSenderExists(TENANT));

        providerService.onRefresh("/config/tenants/TEST/mail-provider.yml", MAIL_CONFIG);

        assertTrue(providerService.isTenantMailSenderExists(TENANT));

        providerService.onRefresh("/config/tenants/TEST/mail-provider.yml", "");

        assertFalse(providerService.isTenantMailSenderExists(TENANT));

    }

    @Test
    public void testMailProviderRecreatedOnRefresh() {

        assertFalse(providerService.isTenantMailSenderExists(TENANT));

        providerService.onRefresh("/config/tenants/TEST/mail-provider.yml", MAIL_CONFIG);

        assertTrue(providerService.isTenantMailSenderExists(TENANT));
        JavaMailSender mailSender1 = providerService.getJavaMailSender(TENANT);

        providerService.onRefresh("/config/tenants/TEST/mail-provider.yml", MAIL_CONFIG);

        assertTrue(providerService.isTenantMailSenderExists(TENANT));
        JavaMailSender mailSender2 = providerService.getJavaMailSender(TENANT);

        assertNotNull(mailSender1);
        assertNotNull(mailSender2);

        assertNotEquals(mailSender1, mailSender2);

    }

}
