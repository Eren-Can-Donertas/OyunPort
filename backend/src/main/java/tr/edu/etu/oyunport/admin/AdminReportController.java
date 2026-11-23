package tr.edu.etu.oyunport.admin;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tr.edu.etu.oyunport.auth.AuthUser;

import java.util.List;

@RestController
@RequestMapping("/api/admin/reports")
public class AdminReportController {

    private final ReportService reports;

    public AdminReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/definitions")
    public List<ReportService.ReportDef> definitions() {
        return reports.definitions();
    }

    @GetMapping("/run/{key}")
    public ReportService.ReportResult run(@AuthenticationPrincipal AuthUser u, @PathVariable String key) {
        return reports.run(u.userId(), key);
    }

    @GetMapping("/plan/{key}")
    public ReportService.PlanResult plan(@AuthenticationPrincipal AuthUser u, @PathVariable String key) {
        return reports.plan(u.userId(), key);
    }
}
