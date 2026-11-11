package com.payrail.payment.auth;

import com.payrail.payment.merchant.ApiKeyGenerator;
import com.payrail.payment.merchant.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates requests that carry "Authorization: Bearer <api key>".
 * Requests without the header pass through; the security rules decide whether that's allowed.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiKeyRepository apiKeyRepository;
    private final AuthenticationEntryPoint entryPoint;

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeyRepository, AuthenticationEntryPoint entryPoint) {
        this.apiKeyRepository = apiKeyRepository;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<AuthenticatedMerchant> merchant = authenticate(header.substring(BEARER.length()).trim());
        if (merchant.isEmpty()) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("Invalid API key"));
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(
                merchant.get(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    private Optional<AuthenticatedMerchant> authenticate(String apiKey) {
        if (apiKey.length() <= ApiKeyGenerator.PREFIX_LENGTH) {
            return Optional.empty();
        }
        String prefix = apiKey.substring(0, ApiKeyGenerator.PREFIX_LENGTH);
        String providedHash = ApiKeyGenerator.sha256Hex(apiKey);

        return apiKeyRepository.findActiveByPrefixWithMerchant(prefix)
                .filter(key -> constantTimeEquals(providedHash, key.getKeyHash()))
                .map(key -> new AuthenticatedMerchant(key.getMerchant().getId(), key.getKeyPrefix()));
    }

    /** Compares in constant time so response timing never reveals how much of a hash matched. */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}