package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : HttpWhatsAppGateway.java
 * - Date      : 2026. 10. 31.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A WhatsApp gateway over HTTP: a JSON POST with to, message and (when set) sender.
 *               The token is sent as a bearer. A gateway that answers 4xx/5xx, or does not answer
 *               within five seconds, throws; the confirmation is logged and never retried.
 * </pre>
 */
@Component
public class HttpWhatsAppGateway implements WhatsAppGateway {

    private final SettingService settings;

    public HttpWhatsAppGateway(SettingService settings) {
        this.settings = settings;
    }

    @Override
    public boolean configured() {
        return StringUtils.hasText(settings.get(SettingKey.WHATSAPP_GATEWAY_URL));
    }

    @Override
    public void send(String number, String message) {
        String url = settings.get(SettingKey.WHATSAPP_GATEWAY_URL);
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("The WhatsApp gateway has no address");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        RestClient rest = RestClient.builder().requestFactory(factory).build();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("to", number);
        body.put("message", message);
        String sender = settings.get(SettingKey.WHATSAPP_SENDER);
        if (StringUtils.hasText(sender)) {
            body.put("sender", sender);
        }
        String token = settings.get(SettingKey.WHATSAPP_TOKEN);
        RestClient.RequestBodySpec request = rest.post().uri(url).contentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(token)) {
            request.header("Authorization", "Bearer " + token);
        }
        request.body(body).retrieve().toBodilessEntity();
    }
}
