package com.yugabyte.yw.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.icegreen.greenmail.util.GreenMail;
import com.yugabyte.yw.common.config.RuntimeConfig;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.forms.CustomerRegisterFormData.SmtpData;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.CustomerConfig;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import play.libs.Json;

@RunWith(JUnitParamsRunner.class)
public class EmailHelperTest extends FakeDBApplication {

  private static final String EMAIL_SUBJECT = "subject";

  private static final String EMAIL_TO = "to@mail.com";

  private static final String EMAIL_TEXT = "Simple email text";

  private static final String EMAIL_TEST_USER = "Gregory";

  private static final String EMAIL_TEST_USER_PWD = "2345-5432";

  private static final String YB_DEFAULT_EMAIL = "test@yugabyte.com";

  @Rule
  public MockitoRule rule = MockitoJUnit.rule();

  @Mock
  private RuntimeConfigFactory configFactory;

  @InjectMocks
  private EmailHelper emailHelper;

  private Customer defaultCustomer;

  @Mock
  private RuntimeConfig<Customer> mockCustomerConfig;

  @Before
  public void setUp() {
    defaultCustomer = ModelFactory.testCustomer();

    when(configFactory.forCustomer(defaultCustomer)).thenReturn(mockCustomerConfig);

    when(mockCustomerConfig.getString("yb.health.default_email")).thenReturn(YB_DEFAULT_EMAIL);
    when(mockCustomerConfig.getString("yb.health.ses_email_username")).thenReturn(EMAIL_TEST_USER);
    when(mockCustomerConfig.getString("yb.health.ses_email_password"))
        .thenReturn(EMAIL_TEST_USER_PWD);
    when(mockCustomerConfig.getBoolean("yb.health.default_ssl")).thenReturn(false);
  }

  @Test
  public void testSendEmail_FilledSmtpData() throws MessagingException, IOException {
    SmtpData smtpData = EmailFixtures.createSmtpData();
    doTestSendEmail(smtpData.smtpServer, smtpData.smtpPort, smtpData,
        "smtp:" + smtpData.smtpServer + ":" + String.valueOf(smtpData.smtpPort));
  }

  private void doTestSendEmail(String serverHost, int serverPort, SmtpData smtpData,
      String expectedSmtpServerName) throws MessagingException, IOException {

    GreenMail mailServer = EmailFixtures.setupMailServer(serverHost, serverPort, smtpData.emailFrom,
        smtpData.smtpUsername, smtpData.smtpPassword);
    try {
      emailHelper.sendEmail(EMAIL_SUBJECT, EMAIL_TO, smtpData,
          Collections.singletonMap("plain/text", EMAIL_TEXT));

      MimeMessage[] messages = mailServer.getReceivedMessages();
      assertNotNull(messages);
      assertEquals(1, messages.length);

      MimeMessage m = messages[0];
      assertEquals(EMAIL_SUBJECT, m.getSubject());
      assertEquals(smtpData.emailFrom, m.getFrom()[0].toString());
      assertEquals(EMAIL_TO, m.getAllRecipients()[0].toString());

      assertTrue(m.getContent() instanceof MimeMultipart);
      MimeMultipart content = (MimeMultipart) m.getContent();
      assertEquals(1, content.getCount());
      assertEquals("plain/text", content.getBodyPart(0).getContentType());
      assertEquals(EMAIL_TEXT,
          IOUtils.toString(content.getBodyPart(0).getInputStream(), StandardCharsets.UTF_8.name()));

      assertEquals(mailServer.getSmtp().getName(), expectedSmtpServerName);
    } finally {
      mailServer.stop();
    }
  }

  @Test
  // @formatter:off
  @Parameters({ "to@mail.com, false, 1",
                "to@mail.com, true, 2",
                ", true, 1",
                ", false, 0" })
  // @formatter:on
  public void testGetDestinations(String emailTo, boolean sendAlertsToYb, int expectedCount) {
    ModelFactory.createAlertConfig(defaultCustomer, emailTo, sendAlertsToYb, false);
    List<String> destinations = emailHelper.getDestinations(defaultCustomer.uuid);
    assertEquals(expectedCount, destinations.size());
    if (!StringUtils.isEmpty(emailTo)) {
      assertTrue(destinations.contains(emailTo));
    }
    if (sendAlertsToYb) {
      assertTrue(destinations.contains(YB_DEFAULT_EMAIL));
    }
  }

  @Test
  public void testGetSmtpData_NoConfig() {
    SmtpData smtpData = emailHelper.getSmtpData(defaultCustomer.uuid);
    assertEquals(YB_DEFAULT_EMAIL, smtpData.emailFrom);
    assertEquals(EMAIL_TEST_USER, smtpData.smtpUsername);
    assertEquals(EMAIL_TEST_USER_PWD, smtpData.smtpPassword);
    assertFalse(smtpData.useSSL);
  }

  @Test
  public void testGetSmtpData_ConfigExistsAndEmailFromFilled() {
    SmtpData testSmtpData = EmailFixtures.createSmtpData();
    CustomerConfig.createSmtpConfig(defaultCustomer.uuid, Json.toJson(testSmtpData));

    SmtpData smtpData = emailHelper.getSmtpData(defaultCustomer.uuid);
    assertEquals(testSmtpData.emailFrom, smtpData.emailFrom);
    assertEquals(testSmtpData.smtpUsername, smtpData.smtpUsername);
    assertEquals(testSmtpData.smtpPassword, smtpData.smtpPassword);
    assertEquals(testSmtpData.useSSL, smtpData.useSSL);
  }

  @Test
  public void testGetSmtpData_ConfigExistsAndEmailEmpty() {
    SmtpData testSmtpData = EmailFixtures.createSmtpData();
    testSmtpData.emailFrom = "";
    CustomerConfig.createSmtpConfig(defaultCustomer.uuid, Json.toJson(testSmtpData));

    SmtpData smtpData = emailHelper.getSmtpData(defaultCustomer.uuid);
    assertEquals(YB_DEFAULT_EMAIL, smtpData.emailFrom);
    assertEquals(testSmtpData.smtpUsername, smtpData.smtpUsername);
    assertEquals(testSmtpData.smtpPassword, smtpData.smtpPassword);
    assertEquals(testSmtpData.useSSL, smtpData.useSSL);
  }
}
