package com.devanshedutech.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/auth")
public class OAuth2RedirectController {

    @GetMapping("/google")
    public void googleRedirect(HttpServletResponse response) throws IOException {
        response.sendRedirect("/auth/authorize/google");
    }
}
