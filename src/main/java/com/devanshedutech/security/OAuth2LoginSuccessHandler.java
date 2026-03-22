package com.devanshedutech.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final String frontendUrl;

    public OAuth2LoginSuccessHandler(@Value("${app.cors.allowed-origins:http://localhost:5173}") String[] allowedOrigins) {
        this.frontendUrl = allowedOrigins.length > 0 ? allowedOrigins[0] : "http://localhost:5173";
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {

        response.setContentType("text/html");
        response.getWriter().write(String.format("""
            <html>
                <body>
                <script>
                    if (window.opener) {
                    window.opener.postMessage({ type: 'OAUTH_AUTH_SUCCESS' }, '*');
                    window.close();
                    } else {
                    window.location.href = '%s/admin';
                    }
                </script>
                <p>Authentication successful. This window should close automatically.</p>
                </body>
            </html>
        """, frontendUrl));
    }
}
