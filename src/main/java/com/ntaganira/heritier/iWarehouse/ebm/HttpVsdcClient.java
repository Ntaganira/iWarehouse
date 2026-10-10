package com.ntaganira.heritier.iWarehouse.ebm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.ebm
 * - File      : HttpVsdcClient.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The business's VSDC (RRA's WAR on its own server) over HTTP: JSON POSTs in UTF-8 to its base URL
 *               (Settings, ebm.vsdc-url), no headers (the VSDC adds RRA's keys itself). A refused connection, a
 *               timeout, an HTTP error or an answer that is not a VSDC envelope is VsdcUnavailableException.
 * </pre>
 */
public class HttpVsdcClient implements VsdcClient {

    private final RestClient rest;
    private final ObjectMapper mapper;

    public HttpVsdcClient(String baseUrl, ObjectMapper mapper, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.rest = RestClient.builder()
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .requestFactory(factory)
                .build();
        this.mapper = mapper;
    }

    @Override
    public Vsdc.Reply<Vsdc.InitData> init(Vsdc.InitRequest request) {
        return post(Vsdc.INIT, request, Vsdc.InitData.class);
    }

    @Override
    public Vsdc.Reply<JsonNode> saveItem(Vsdc.ItemRequest request) {
        return post(Vsdc.SAVE_ITEM, request, JsonNode.class);
    }

    @Override
    public Vsdc.Reply<Vsdc.Signature> saveSale(Vsdc.SaleRequest request) {
        return post(Vsdc.SAVE_SALE, request, Vsdc.Signature.class);
    }

    @Override
    public boolean simulated() {
        return false;
    }

    private <T> Vsdc.Reply<T> post(String path, Object body, Class<T> dataType) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the VSDC request " + path, e);
        }
        byte[] answer;
        try {
            answer = rest.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(json.getBytes(StandardCharsets.UTF_8))
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            throw new VsdcUnavailableException("HTTP " + e.getStatusCode().value() + " from the VSDC at " + path, json);
        } catch (ResourceAccessException e) {
            throw new VsdcUnavailableException("The VSDC does not answer at " + path + ": " + rootMessage(e), json);
        } catch (RestClientException e) {
            throw new VsdcUnavailableException("The VSDC call " + path + " failed: " + rootMessage(e), json);
        }
        String response = answer == null ? "" : new String(answer, StandardCharsets.UTF_8);
        try {
            JavaType type = mapper.getTypeFactory().constructParametricType(Vsdc.Envelope.class, dataType);
            Vsdc.Envelope<T> envelope = mapper.readValue(response, type);
            if (envelope == null || envelope.resultCd() == null) {
                throw new VsdcUnavailableException("The VSDC answer to " + path + " has no result code", json);
            }
            return new Vsdc.Reply<>(json, response, envelope);
        } catch (JsonProcessingException e) {
            throw new VsdcUnavailableException("The VSDC answer to " + path + " is not JSON: " + abbreviate(response), json);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }
}
