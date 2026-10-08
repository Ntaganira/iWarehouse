package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.DataChangeLog;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.StringJoiner;

/**
 * Data Changes screens (SRS AUD-10, AUD-11): list of before/after changes, one change in detail,
 * and the history of one record (linked from each record's History tab).
 */
@Controller
@RequestMapping("/audit")
@PreAuthorize("hasAuthority('PERM_VIEW_DATA_CHANGES')")
public class DataChangeController {

    private final DataChangeService service;

    public DataChangeController(DataChangeService service) {
        this.service = service;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) String entityType,
                       @RequestParam(required = false) String entityId,
                       @RequestParam(required = false) String operation,
                       @RequestParam(required = false) String username,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       Model model) {
        Page<DataChangeLog> changes = service.findPage(entityType, entityId, operation, username, from, to,
                Paging.page(page), Paging.SIZE);
        model.addAttribute("changes", changes);
        model.addAttribute("entityTypes", service.entityTypes());
        model.addAttribute("entityType", entityType);
        model.addAttribute("entityId", entityId);
        model.addAttribute("operation", operation);
        model.addAttribute("username", username);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("paginationQuery", query(entityType, entityId, operation, username, from, to));
        return "audit/list";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, @RequestParam(defaultValue = "0") int page, Model model) {
        DataChangeLog change = service.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("change", change);
        model.addAttribute("fields", DataChangeService.fieldChanges(change));
        // One action can change hundreds of records (posting a receipt): paged like every list
        model.addAttribute("related", Paging.of(service.sameRequest(change.getRequestId()).stream()
                .filter(c -> !c.getId().equals(change.getId())).toList(), page));
        return "audit/view";
    }

    @GetMapping("/history/{entityType}/{entityId}")
    public String history(@PathVariable String entityType, @PathVariable String entityId,
                          @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("entityType", entityType);
        model.addAttribute("entityId", entityId);
        model.addAttribute("changes", service.history(entityType, entityId, Paging.page(page), Paging.SIZE));
        return "audit/history";
    }

    private static String query(String entityType, String entityId, String operation, String username,
                                LocalDate from, LocalDate to) {
        StringJoiner q = new StringJoiner("&");
        add(q, "entityType", entityType);
        add(q, "entityId", entityId);
        add(q, "operation", operation);
        add(q, "username", username);
        add(q, "from", from == null ? null : from.toString());
        add(q, "to", to == null ? null : to.toString());
        return q.toString();
    }

    private static void add(StringJoiner q, String name, String value) {
        if (StringUtils.hasText(value)) {
            q.add(name + "=" + URLEncoder.encode(value.trim(), StandardCharsets.UTF_8));
        }
    }
}
