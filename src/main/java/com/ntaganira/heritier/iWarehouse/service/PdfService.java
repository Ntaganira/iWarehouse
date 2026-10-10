package com.ntaganira.heritier.iWarehouse.service;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PdfService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Renders a Thymeleaf template to PDF with OpenHTMLToPDF (pure Java, as iVura) for report exports and
 *               document copies (RPT-07). The template is well-formed XHTML (self-closed void tags, &amp;#160; not
 *               &amp;nbsp;), sets its page in CSS (@page size, margins, running footer) and may use the messages of the
 *               request's language and the @num formatter. The base-14 fonts print Latin text (accents, ², ·, —).
 * </pre>
 */
@Service
public class PdfService {

    private final SpringTemplateEngine templateEngine;
    private final ApplicationContext applicationContext;

    public PdfService(SpringTemplateEngine templateEngine, ApplicationContext applicationContext) {
        this.templateEngine = templateEngine;
        this.applicationContext = applicationContext;
    }

    public byte[] render(String template, Map<String, Object> model) {
        Context context = new Context(LocaleContextHolder.getLocale());
        context.setVariables(model);
        // Lets the template call beans (@num) as the pages do
        context.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(applicationContext, null));
        String html = templateEngine.process(template, context);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.useDefaultPageSize(210f, 297f, BaseRendererBuilder.PageSizeUnits.MM);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render " + template + " as PDF", e);
        }
    }
}
