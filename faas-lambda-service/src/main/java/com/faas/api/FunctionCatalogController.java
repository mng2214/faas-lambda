package com.faas.api;

import com.faas.LocalLambdaPlatform;
import com.faas.dto.FunctionInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/functions")
public class FunctionCatalogController {

    private final LocalLambdaPlatform platform;

    public FunctionCatalogController(LocalLambdaPlatform platform) {
        this.platform = platform;
    }

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
}
