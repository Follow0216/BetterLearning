package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.betterlearning.server.security.AuthenticatedAccount;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CitationControllerTests {
    private final CitationService service = mock(CitationService.class);
    private final CitationController controller = new CitationController(service);
    private final AuthenticatedAccount account = new AuthenticatedAccount(7L, "student", "USER", 0, "jti");

    @Test
    void resolveAndAssetUsePrincipalAndDisableCaching() {
        byte[] image = {1, 2, 3};
        when(service.asset(7L, "citation", 3)).thenReturn(image);
        var resolved = controller.resolve(account, "citation");
        var asset = controller.asset(account, "citation", 3);
        verify(service).resolve(7L, "citation");
        verify(service).asset(7L, "citation", 3);
        assertThat(resolved.getHeaders().getCacheControl()).contains("no-store");
        assertThat(resolved.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(asset.getHeaders().getCacheControl()).contains("no-store");
        assertThat(asset.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(asset.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(asset.getBody()).isEqualTo(image);
    }
}
