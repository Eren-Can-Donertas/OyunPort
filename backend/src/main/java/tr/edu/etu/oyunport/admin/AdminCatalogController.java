package tr.edu.etu.oyunport.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tr.edu.etu.oyunport.admin.AdminCatalogService.RefKind;
import tr.edu.etu.oyunport.auth.AuthUser;
import tr.edu.etu.oyunport.common.ApiException;
import tr.edu.etu.oyunport.common.Page;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminCatalogController {

    private final AdminCatalogService svc;

    public AdminCatalogController(AdminCatalogService svc) {
        this.svc = svc;
    }

    public record RefRequest(@NotBlank @Size(max = 120) String name, @Size(max = 60) String country) {}

    public record ActiveRequest(boolean active) {}

    @GetMapping("/products")
    public Page<AdminCatalogService.AdminProduct> products(@AuthenticationPrincipal AuthUser u,
                                                           @RequestParam(required = false) String q,
                                                           @RequestParam(required = false) String type,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return svc.list(u.userId(), q, type, page, size);
    }

    @GetMapping("/products/{id}")
    public AdminCatalogService.AdminProduct product(@AuthenticationPrincipal AuthUser u, @PathVariable long id) {
        return svc.get(u.userId(), id);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminCatalogService.AdminProduct create(@AuthenticationPrincipal AuthUser u,
                                                   @RequestBody AdminCatalogService.ProductInput in) {
        return svc.create(u.userId(), in);
    }

    @PutMapping("/products/{id}")
    public AdminCatalogService.AdminProduct update(@AuthenticationPrincipal AuthUser u, @PathVariable long id,
                                                   @RequestBody AdminCatalogService.ProductInput in) {
        return svc.update(u.userId(), id, in);
    }

    @PutMapping("/products/{id}/active")
    public AdminCatalogService.AdminProduct setActive(@AuthenticationPrincipal AuthUser u, @PathVariable long id,
                                                      @RequestBody ActiveRequest r) {
        return svc.setActive(u.userId(), id, r.active());
    }

    @GetMapping("/{kind}")
    public List<AdminCatalogService.Ref> refs(@AuthenticationPrincipal AuthUser u, @PathVariable String kind) {
        return svc.listRefs(u.userId(), kind(kind));
    }

    @PostMapping("/{kind}")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminCatalogService.Ref createRef(@AuthenticationPrincipal AuthUser u, @PathVariable String kind,
                                             @Valid @RequestBody RefRequest r) {
        return svc.createRef(u.userId(), kind(kind), r.name(), r.country());
    }

    @PutMapping("/{kind}/{id}")
    public AdminCatalogService.Ref updateRef(@AuthenticationPrincipal AuthUser u, @PathVariable String kind,
                                             @PathVariable long id, @Valid @RequestBody RefRequest r) {
        return svc.updateRef(u.userId(), kind(kind), id, r.name(), r.country());
    }

    @DeleteMapping("/{kind}/{id}")
    public ResponseEntity<Void> deleteRef(@AuthenticationPrincipal AuthUser u, @PathVariable String kind,
                                          @PathVariable long id) {
        svc.deleteRef(u.userId(), kind(kind), id);
        return ResponseEntity.noContent().build();
    }

    private static RefKind kind(String path) {
        return switch (path) {
            case "publishers" -> RefKind.PUBLISHER;
            case "developers" -> RefKind.DEVELOPER;
            case "genres" -> RefKind.GENRE;
            default -> throw ApiException.notFound("Bilinmeyen kaynak: " + path);
        };
    }
}
