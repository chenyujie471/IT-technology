package com.example.ittechnology.controller;

import com.example.ittechnology.service.AiProxyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RestController
@RequestMapping("/api/asset")
@RequiredArgsConstructor
public class AssetController {

    private final AiProxyService proxyService;

    @PostMapping(value = "/upload", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> upload(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body,
            HttpServletResponse response) {

        log.info("Asset upload request received [site={}]", site);

        StreamingResponseBody stream = out ->
                proxyService.proxyPostStream(site, "/v1/assets/upload", auth, body, Duration.ofSeconds(120), out, response);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(stream);
    }

    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> list(
            @RequestHeader("Authorization") String auth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String purpose,
            @RequestParam(name = "sort_order", defaultValue = "Desc") String sortOrder,
            @RequestParam(name = "group_id", required = false) String groupId,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            HttpServletResponse response) {

        log.info("Asset list request: page={}, pageSize={} [site={}]", page, pageSize, site);

        StringBuilder query = new StringBuilder("/v1/assets?page=" + page + "&page_size=" + pageSize);
        if (purpose != null && !purpose.isEmpty()) {
            query.append("&purpose=").append(java.net.URLEncoder.encode(purpose, java.nio.charset.StandardCharsets.UTF_8));
        }
        query.append("&sort_order=").append(sortOrder);
        if (groupId != null && !groupId.isEmpty()) {
            query.append("&group_id=").append(groupId);
        }

        AtomicInteger status = new AtomicInteger(200);
        String result = proxyService.proxyGet(site, query.toString(), auth, Duration.ofSeconds(30), status);
        response.setHeader("X-Upstream-Status", String.valueOf(status.get()));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(result);
    }

    @GetMapping(value = "/{assetId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getOne(
            @RequestHeader("Authorization") String auth,
            @PathVariable String assetId,
            @RequestParam(name = "group_id", required = false) String groupId,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            HttpServletResponse response) {

        log.info("Asset get request: {} [site={}]", assetId, site);

        String path = "/v1/assets/" + assetId;
        if (groupId != null && !groupId.isEmpty()) {
            path += "?group_id=" + groupId;
        }

        AtomicInteger status = new AtomicInteger(200);
        String result = proxyService.proxyGet(site, path, auth, Duration.ofSeconds(30), status);
        response.setHeader("X-Upstream-Status", String.valueOf(status.get()));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(result);
    }
}
