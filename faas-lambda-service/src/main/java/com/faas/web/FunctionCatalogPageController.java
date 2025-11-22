package com.faas.web;

import com.faas.LocalLambdaPlatform;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/ui/functions")
public class FunctionCatalogPageController {

    private final LocalLambdaPlatform platform;

    public FunctionCatalogPageController(LocalLambdaPlatform platform) {
        this.platform = platform;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("functions", platform.listFunctions());
        return "functions/list";
    }
}
