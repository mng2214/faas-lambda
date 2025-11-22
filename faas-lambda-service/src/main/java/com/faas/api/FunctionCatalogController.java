package com.faas.api;

import com.faas.core.LocalLambdaPlatform;
import com.faas.dto.FunctionInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/functions")
@RequiredArgsConstructor
public class FunctionCatalogController {

    private final LocalLambdaPlatform platform;

    @GetMapping
    public List<FunctionInfo> listFunctions() {
        return platform.listFunctions();
    }

    @GetMapping("/{name}")
    public ResponseEntity<FunctionInfo> getFunction(@PathVariable String name) {
        return platform.listFunctions().stream()
                .filter(f -> f.getName().equals(name))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return platform.getSystemMetrics();
    }

    @GetMapping("/list")
    public List<String> list(
            @RequestParam String key,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return platform.getListForKey(key, page, size);
    }

    @GetMapping("/results/{functionName}")
    public List<String> getResults(
            @PathVariable String functionName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return platform.getFunctionResults(functionName, page, size);
    }

    @GetMapping("/errors/{functionName}")
    public List<String> getErrors(
            @PathVariable String functionName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return platform.getFunctionErrors(functionName, page, size);
    }

}
