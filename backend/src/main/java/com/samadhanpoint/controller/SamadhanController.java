package com.samadhanpoint.controller;

import com.samadhanpoint.service.SamadhanService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api")
public class SamadhanController {
    private final SamadhanService service;
    public SamadhanController(SamadhanService service) { this.service = service; }

    @GetMapping("/health") public Map<String,Object> health() { return service.health(); }
    @GetMapping("/health/db") public Map<String,Object> dbHealth() { return service.health(); }

    @PostMapping("/auth/register") public ResponseEntity<Map<String,Object>> register(@RequestBody Map<String,Object> body) { return response(service.register(body), HttpStatus.CREATED); }
    @PostMapping("/auth/login") public ResponseEntity<Map<String,Object>> login(@RequestBody Map<String,Object> body) { return response(service.login(str(body,"username",str(body,"email","")), str(body,"password",""))); }
    @PostMapping("/auth/forgot-password") public ResponseEntity<Map<String,Object>> forgotPassword(@RequestBody Map<String,Object> body) { return response(service.requestPasswordOtp(body)); }
    @PostMapping("/auth/reset-password") public ResponseEntity<Map<String,Object>> resetPassword(@RequestBody Map<String,Object> body) { return response(service.resetPasswordWithOtp(body)); }
    @GetMapping("/auth/verify") public ResponseEntity<Map<String,Object>> verify(HttpServletRequest req) { Map<String,Object> u=user(req); return u==null ? ResponseEntity.status(401).body(Map.of("success",false,"valid",false)) : ResponseEntity.ok(Map.of("success",true,"valid",true,"userSession",u)); }
    @PostMapping("/auth/logout") public Map<String,Object> logout(HttpServletRequest req) { service.logout(token(req)); return Map.of("success",true); }
    @PutMapping("/profile") public ResponseEntity<Map<String,Object>> profile(@RequestBody Map<String,Object> body,HttpServletRequest req){return response(service.updateProfile(body,user(req)));}

    @GetMapping("/reverse-geocode") public ResponseEntity<Map<String,Object>> reverse(@RequestParam double lat,@RequestParam double lon){ Map<String,Object> r=service.reverseGeocode(lat,lon); return r.isEmpty()?ResponseEntity.status(404).body(Map.of("success",false,"error","Reverse geocoding unavailable")):ResponseEntity.ok(Map.of("success",true,"data",r)); }
    @GetMapping("/wards") public Map<String,Object> wards(){ return Map.of("success",true,"data",service.wards()); }
    @GetMapping("/ward-by-pincode/{pincode}") public ResponseEntity<Map<String,Object>> ward(@PathVariable String pincode) { Map<String,Object> r=service.wardForPincode(pincode); return r==null ? ResponseEntity.status(404).body(Map.of("success",false,"error","Pincode not mapped")) : ResponseEntity.ok(Map.of("success",true,"data",r)); }
    @PostMapping("/triage/analyze") public Map<String,Object> triage(@RequestBody Map<String,Object> body) { return service.triage(str(body,"text",""),str(body,"ward",service.defaultWard())); }

    @GetMapping("/grievances") public Map<String,Object> grievances(HttpServletRequest req,@RequestParam(required=false) String department,@RequestParam(required=false) String language,@RequestParam(required=false) String ward,@RequestParam(required=false) String status) { Map<String,Object> u=user(req); List<Map<String,Object>> data=(u!=null&&Set.of("ADMIN","AUDITOR").contains(str(u,"role","")))?service.filtered(u,department,language,ward,status):service.listGrievances(u); return Map.of("success",true,"total",data.size(),"data",data); }
    @GetMapping("/grievances/my-complaints") public Map<String,Object> mine(HttpServletRequest req) { return Map.of("success",true,"data",service.listGrievances(user(req))); }
    @GetMapping("/grievances/my-tasks") public Map<String,Object> tasks(HttpServletRequest req) { return Map.of("success",true,"data",service.listGrievances(user(req))); }
    @GetMapping("/grievances/track/{id}") public ResponseEntity<Map<String,Object>> track(@PathVariable String id,HttpServletRequest req) { Map<String,Object> u=user(req), g=service.getGrievance(id); return g==null||!service.canView(u,g)?ResponseEntity.status(404).body(Map.of("success",false,"error","Grievance not found")):ResponseEntity.ok(Map.of("success",true,"data",g,"timeline",service.timeline(id,u))); }
    @GetMapping("/grievances/{id}") public ResponseEntity<Map<String,Object>> get(@PathVariable String id,HttpServletRequest req) { return track(id,req); }
    @PostMapping("/grievances") public ResponseEntity<Map<String,Object>> create(@RequestBody Map<String,Object> body,HttpServletRequest req) { return response(service.createGrievance(body,user(req)),HttpStatus.CREATED); }
    @PutMapping("/grievances/{id}") public ResponseEntity<Map<String,Object>> put(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest req){return response(service.update(id,body,user(req)));}
    @PatchMapping("/grievances/{id}") public ResponseEntity<Map<String,Object>> patch(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest req){return response(service.update(id,body,user(req)));}
    @DeleteMapping("/grievances/{id}") public ResponseEntity<Map<String,Object>> delete(@PathVariable String id,HttpServletRequest req){return response(service.deleteGrievance(id,user(req)));}
    @GetMapping("/grievances/{id}/workers") public Map<String,Object> workers(@PathVariable String id,HttpServletRequest req){return Map.of("success",true,"data",service.workersForGrievance(id,user(req)));}
    @PostMapping("/grievances/{id}/assign") public ResponseEntity<Map<String,Object>> assign(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest req){return response(service.assign(id,str(body,"workerId",""),user(req)));}
    @PostMapping("/grievances/{id}/verify") public ResponseEntity<Map<String,Object>> verify(@PathVariable String id,HttpServletRequest req){return response(service.verifyResolution(id,user(req)));}
    @PostMapping("/grievances/{id}/appeal") public ResponseEntity<Map<String,Object>> appeal(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest req){return response(service.createAppeal(id,body,user(req)));}

    @GetMapping("/appeals") public Map<String,Object> appeals(HttpServletRequest req){return Map.of("success",true,"data",service.appeals(user(req)));}
    @PostMapping("/appeals/{id}/review") public ResponseEntity<Map<String,Object>> review(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest req){return response(service.reviewAppeal(id,body,user(req)));}
    @GetMapping("/analytics") public Map<String,Object> analytics(HttpServletRequest req){return service.analytics(user(req));}
    @GetMapping("/admin/wards") public Map<String,Object> adminWards(HttpServletRequest req){return service.adminWardOverview(user(req));}
    @GetMapping("/admin/preferences/widgets") public Map<String,Object> adminWidgetPreferences(HttpServletRequest req){return service.adminWidgetPreferences(user(req));}
    @PutMapping("/admin/preferences/widgets") public Map<String,Object> saveAdminWidgetPreferences(@RequestBody Map<String,Object> body,HttpServletRequest req){return service.saveAdminWidgetPreferences(body,user(req));}
    @PostMapping("/admin/preferences/widgets/reset") public Map<String,Object> resetAdminWidgetPreferences(HttpServletRequest req){return service.resetAdminWidgetPreferences(user(req));}
    @GetMapping("/evaluation/summary") public ResponseEntity<Map<String,Object>> evaluation(HttpServletRequest req){ Map<String,Object> r=service.evaluationSummary(user(req)); return ResponseEntity.status(Boolean.TRUE.equals(r.get("success"))?HttpStatus.OK:HttpStatus.FORBIDDEN).body(r);}
    @GetMapping("/managed-users") public Map<String,Object> users(HttpServletRequest req){return Map.of("success",true,"data",service.managedUsersFor(user(req)));}
    @PostMapping("/managed-users") public ResponseEntity<Map<String,Object>> userCreate(@RequestBody Map<String,Object> body,HttpServletRequest req){return response(service.managedCreate(body,user(req)));}
    @PostMapping("/managed-users/{id}/reset-password") public ResponseEntity<Map<String,Object>> userPasswordReset(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest req){return response(service.adminResetPassword(id,body,user(req)));}
    @PutMapping("/managed-users/{id}") public ResponseEntity<Map<String,Object>> userUpdate(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest req){return response(service.managedUpdate(id,body,user(req)));}
    @DeleteMapping("/managed-users/{id}") public ResponseEntity<Map<String,Object>> userDelete(@PathVariable String id,HttpServletRequest req){return response(service.managedDelete(id,user(req)));}
    @GetMapping("/departments") public Map<String,Object> departments(){return Map.of("success",true,"data",service.departments());}
    @PostMapping("/departments") public ResponseEntity<Map<String,Object>> deptCreate(@RequestBody Map<String,Object> body,HttpServletRequest req){return response(service.departmentCreate(body,user(req)));}
    @PutMapping("/departments/{id}") public ResponseEntity<Map<String,Object>> deptUpdate(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest req){return response(service.departmentUpdate(id,body,user(req)));}
    @DeleteMapping("/departments/{id}") public ResponseEntity<Map<String,Object>> deptDelete(@PathVariable String id,HttpServletRequest req){return response(service.departmentDelete(id,user(req)));}
    @GetMapping("/managed-users/audit-logs") public Map<String,Object> audit(HttpServletRequest req){return Map.of("success",true,"data",service.auditLogs());}
    @GetMapping(value="/reports/complaints.csv",produces="text/csv") public ResponseEntity<byte[]> report(HttpServletRequest req,@RequestParam(required=false) String department){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=samadhanpoint-complaints.csv").body(service.exportCsv(user(req),department).getBytes(StandardCharsets.UTF_8));}
    @GetMapping(value="/reports/complaints.pdf", produces="application/pdf") public ResponseEntity<byte[]> reportPdf(HttpServletRequest req,@RequestParam(required=false) String department){ byte[] pdf=service.exportPdf(user(req),department); return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=samadhanpoint-complaints.pdf").body(pdf); }
    @GetMapping("/reports/department") public Map<String,Object> departmentReport(HttpServletRequest req,@RequestParam(required=false) String department){return service.departmentReport(user(req),department);}
    @PostMapping("/assistant/chat") public Map<String,Object> assistant(@RequestBody Map<String,Object> body,HttpServletRequest req){return service.assistant(str(body,"text",str(body,"messages","")),str(body,"language","en"),user(req));}
    @GetMapping("/notifications") public Map<String,Object> notifications(HttpServletRequest req){return Map.of("success",true,"data",service.notifications(user(req)));}
    @GetMapping("/notifications/unread-count") public Map<String,Object> unreadNotifications(HttpServletRequest req){return Map.of("success",true,"count",service.unreadNotificationCount(user(req)));}
    @PatchMapping("/notifications/{id}/read") public Map<String,Object> readNotification(@PathVariable String id,HttpServletRequest req){return service.markNotification(id,user(req));}

    private ResponseEntity<Map<String,Object>> response(Map<String,Object> result){return response(result,HttpStatus.OK);}
    private ResponseEntity<Map<String,Object>> response(Map<String,Object> result,HttpStatus successStatus){return ResponseEntity.status(Boolean.TRUE.equals(result.get("success"))?successStatus:HttpStatus.BAD_REQUEST).body(result);}
    private Map<String,Object> user(HttpServletRequest req){String token=token(req);return token==null?null:service.session(token);}
    private String token(HttpServletRequest req){String h=req.getHeader("Authorization");return h!=null&&h.startsWith("Bearer ")?h.substring(7):null;}
    private String str(Map<String,Object> m,String k,String d){if(m==null)return d;Object v=m.get(k);return v==null?d:String.valueOf(v);}
}
