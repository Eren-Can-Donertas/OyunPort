package tr.edu.etu.oyunport.library;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tr.edu.etu.oyunport.auth.AuthUser;

import java.util.List;

@RestController
@RequestMapping("/api/library")
public class LibraryController {

    private final LibraryService library;

    public LibraryController(LibraryService library) {
        this.library = library;
    }

    public record RecordRequest(@Min(1) @Max(1440) int minutes) {}

    @GetMapping
    public List<LibraryService.LibraryItem> list(@AuthenticationPrincipal AuthUser user) {
        return library.list(user.userId());
    }

    @GetMapping("/{libraryId}/sessions")
    public List<LibraryService.SessionView> sessions(@AuthenticationPrincipal AuthUser user, @PathVariable long libraryId) {
        return library.sessions(user.userId(), libraryId);
    }

    @PostMapping("/{libraryId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public LibraryService.SessionView start(@AuthenticationPrincipal AuthUser user, @PathVariable long libraryId) {
        return library.start(user.userId(), libraryId);
    }

    @PostMapping("/{libraryId}/sessions/{sessionId}/end")
    public LibraryService.SessionView end(@AuthenticationPrincipal AuthUser user, @PathVariable long libraryId,
                                          @PathVariable long sessionId) {
        return library.end(user.userId(), libraryId, sessionId);
    }

    @PostMapping("/{libraryId}/sessions/record")
    @ResponseStatus(HttpStatus.CREATED)
    public LibraryService.SessionView record(@AuthenticationPrincipal AuthUser user, @PathVariable long libraryId,
                                             @Valid @RequestBody RecordRequest r) {
        return library.record(user.userId(), libraryId, r.minutes());
    }
}
