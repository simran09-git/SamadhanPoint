package com.samadhanpoint.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.samadhanpoint.evaluation.SlaPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class SamadhanService {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final Map<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();
    private final long startedAt = System.currentTimeMillis();
    private long requestCount = 0;
    private long aiCallCount = 0;
    private long aiSuccessCount = 0;
    private long aiFailureCount = 0;
    private volatile long lastAiLatencyMs = 0;
    private final String groqKey = System.getenv().getOrDefault("GROQ_API_KEY", "");
    private final String groqModel = System.getenv().getOrDefault("GROQ_MODEL", "openai/gpt-oss-120b");
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile boolean seedsReady = false;

    private static final Map<String, String> DEPARTMENT = Map.of(
        "WATER_SUPPLY", "Department of Hydraulic Engineering & Water Supply",
        "ROADS_POTHOLES", "Department of Roads & Traffic Infrastructure",
        "WASTE_MANAGEMENT", "Department of Solid Waste Management",
        "ELECTRICITY_STREETLIGHTS", "Department of Electrical & Public Lighting",
        "PUBLIC_HEALTH", "Department of Public Health & Sanitation",
        "PUBLIC_TRANSPORT", "Department of Municipal Transport & Undertakings",
        "URBAN_SANITATION", "Department of Sewerage & Drainage Operations",
        "OTHER", "General Citizen Grievance Cell"
    );

    private static final Map<String, Integer> CATEGORY_SLA = Map.of(
        "WATER_SUPPLY", 48, "ROADS_POTHOLES", 72, "WASTE_MANAGEMENT", 24,
        "ELECTRICITY_STREETLIGHTS", 72, "PUBLIC_HEALTH", 48, "PUBLIC_TRANSPORT", 96,
        "URBAN_SANITATION", 36, "OTHER", 72
    );

    private static final Map<String, String> LANGUAGE_NAMES = Map.of(
        "en", "English", "hi", "Hindi", "mr", "Marathi"
    );



    public SamadhanService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public Map<String, Object> health() {
        requestCount++;
        boolean database = true;
        try { db.queryForObject("SELECT 1", Integer.class); } catch (Exception e) { database = false; }
        return Map.of(
            "status", database ? "HEALTHY" : "DEGRADED",
            "database", database ? "ONLINE" : "OFFLINE",
            "version", "2.0.0",
            "uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000,
            "totalRequests", requestCount,
            "aiCallCount", aiCallCount,
            "aiSuccessCount", aiSuccessCount,
            "aiFailureCount", aiFailureCount,
            "lastAiLatencyMs", lastAiLatencyMs
        );
    }

    public Map<String, Object> register(Map<String, Object> b) {
        ensureSeeds();
        String name = safe(b, "name").trim();
        String email = safe(b, "email").trim().toLowerCase(Locale.ROOT);
        String phone = safe(b, "phone").trim();
        String password = safe(b, "password");
        String address = safe(b, "address").trim();
        String pincode = safe(b, "pincode").trim();
        Map<String,Object> regGps = b.get("location") instanceof Map<?,?> m ? toStringMap(m) : Map.of();
        Double regLat = numberObject(regGps.get("latitude"));
        Double regLon = numberObject(regGps.get("longitude"));
        if (regLat != null && regLon != null) {
            Map<String,Object> resolved = reverseGeocode(regLat, regLon);
            if (!resolved.isEmpty() && Boolean.TRUE.equals(resolved.get("resolved"))) {
                pincode = safe(resolved,"pincode",pincode);
                if (address.isBlank()) address = safe(resolved,"address");
            }
        }
        String language = safe(b, "preferredLanguage", "en");
        if (!Set.of("en","hi","mr").contains(language)) language = "en";
        if (name.isBlank() || email.isBlank() || password.length() < 6 || pincode.isBlank()) {
            return error("Name, email, password (6+ characters) and pincode are required.");
        }
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) return error("Enter a valid email address.");
        Map<String, Object> location = wardForPincode(pincode);
        if (location == null) return error("Pincode is not mapped. Try 400063, 400064, 400067 or another supported Mumbai demo pincode.");
        String id = "USR-" + UUID.randomUUID();
        try {
            db.update("INSERT INTO sp_users(id,username,email,password_value,full_name,phone,role,department,ward_jurisdiction,preferred_language,address,pincode,zone,blocked) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,false)",
                id, email, email, hash(password), name, phone, "CITIZEN", DEPARTMENT.get("OTHER"),
                location.get("ward") + " / " + location.get("zone"), language, address, pincode, location.get("zone"));
            Map<String, Object> user = userRow(db.queryForMap("SELECT * FROM sp_users WHERE id=?", id));
            return sessionResponse(user);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            return error(msg.contains("duplicate") || msg.contains("unique") ? "Email already registered." : "Registration failed. Please try again.");
        }
    }

    public Map<String, Object> login(String username, String password) {
        ensureSeeds();
        String q = safe(username).trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM sp_users WHERE lower(username)=? OR lower(email)=? LIMIT 1", q, q);
        if (rows.isEmpty()) return error("Invalid username/email or password.");
        Map<String, Object> row = rows.get(0);
        if (Boolean.TRUE.equals(row.get("blocked"))) return error("This account is blocked.");
        if (!hash(password).equals(String.valueOf(row.get("password_value")))) return error("Invalid username/email or password.");
        db.update("UPDATE sp_users SET last_login_at=CURRENT_TIMESTAMP WHERE id=?", row.get("id"));
        return sessionResponse(userRow(db.queryForMap("SELECT * FROM sp_users WHERE id=?", row.get("id"))));
    }

    private Map<String, Object> sessionResponse(Map<String, Object> user) {
        String token = UUID.randomUUID().toString();
        user.put("token", token);
        sessions.put(token, new LinkedHashMap<>(user));
        return Map.of("success", true, "token", token, "userSession", user);
    }

    public Map<String, Object> session(String token) { return token == null ? null : sessions.get(token); }
    public void logout(String token) { if (token != null) sessions.remove(token); }

    @Transactional
    public Map<String, Object> updateProfile(Map<String, Object> body, Map<String, Object> user) {
        if (user == null) return error("Unauthorized");
        String id = safe(user, "id");
        String language = safe(body, "preferredLanguage", safe(user, "preferredLanguage", "en"));
        if (!Set.of("en","hi","mr").contains(language)) language = "en";
        String pincode = safe(body, "pincode", safe(user, "pincode"));
        Map<String,Object> loc = pincode.isBlank() ? null : wardForPincode(pincode);
        if (!pincode.isBlank() && loc == null) return error("Pincode is not mapped in the demo ward directory.");
        String ward = loc == null ? safe(user, "wardJurisdiction", "ALL_WARDS") : safe(loc, "ward") + " / " + safe(loc, "zone");
        db.update("UPDATE sp_users SET full_name=COALESCE(?,full_name),phone=COALESCE(?,phone),address=COALESCE(?,address),pincode=COALESCE(?,pincode),zone=COALESCE(?,zone),ward_jurisdiction=COALESCE(?,ward_jurisdiction),preferred_language=COALESCE(?,preferred_language) WHERE id=?",
            nullable(body,"name"), nullable(body,"phone"), nullable(body,"address"), nullable(body,"pincode"), loc==null?null:safe(loc,"zone"), ward, language, id);
        Map<String,Object> fresh=userRow(db.queryForMap("SELECT * FROM sp_users WHERE id=?",id));
        sessions.values().removeIf(v -> Objects.equals(safe(v,"id"), id));
        String token=UUID.randomUUID().toString(); fresh.put("token",token); sessions.put(token,new LinkedHashMap<>(fresh));
        audit(fresh,id,"PROFILE_UPDATED",Map.of("language",language,"ward",ward));
        return Map.of("success",true,"token",token,"userSession",fresh);
    }


    private synchronized void ensureSeeds() {
        if (seedsReady) return;
        ensureWardMaster();
        seedUser("ADMIN-1", "admin", "admin@mumbai.gov.in", System.getenv().getOrDefault("BOOTSTRAP_ADMIN_PASSWORD", "ChangeMe@123"), "Municipal Central Commissioner", "9999990001", "ADMIN", "Central Municipal Administration", "ALL_WARDS", "en");
        removeLegacyDemoAccounts();
        seedWelcomeNotifications();
        repairRoutingAndAssignments();
        seedsReady = true;
    }

    private void ensureWardMaster() {
        try {
            if (count("SELECT COUNT(*) FROM sp_wards") >= 24) return;
            String[][] wards={
                {"W01","A","Ward A","Zone 1"},{"W02","B","Ward B","Zone 1"},{"W03","C","Ward C","Zone 2"},{"W04","D","Ward D","Zone 2"},{"W05","E","Ward E","Zone 1"},
                {"W06","F-N","Ward F North","Zone 1"},{"W07","F-S","Ward F South","Zone 1"},{"W08","G-N","Ward G North","Zone 1"},{"W09","G-S","Ward G South","Zone 1"},
                {"W10","H-E","Ward H East","Zone 2"},{"W11","H-W","Ward H West","Zone 2"},{"W12","K-E","Ward K East","Zone 2"},{"W13","K-W","Ward K West","Zone 2"},{"W14","L","Ward L","Zone 2"},
                {"W15","M-E","Ward M East","Zone 3"},{"W16","M-W","Ward M West","Zone 2"},{"W17","N","Ward N","Zone 3"},{"W18","P-N","Ward P North","Zone 3"},{"W19","P-S","Ward P South","Zone 3"},
                {"W20","R-C","Ward R Central","Zone 3"},{"W21","R-N","Ward R North","Zone 3"},{"W22","R-S","Ward R South","Zone 3"},{"W23","S","Ward S","Zone 3"},{"W24","T","Ward T","Zone 3"}
            };
            for(String[] w:wards) db.update("INSERT INTO sp_wards(id,code,name,zone,active) VALUES(?,?,?,?,true) ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name,zone=EXCLUDED.zone,active=true",w[0],w[1],w[2],w[3]);
        } catch(Exception ignored) {}
    }

    public List<Map<String,Object>> wards() {
        ensureSeeds();
        return db.queryForList("SELECT id,code,name,zone,active FROM sp_wards WHERE active=true ORDER BY id");
    }

    public String defaultWard() {
        ensureSeeds();
        Map<String,Object> w=db.queryForMap("SELECT name,zone FROM sp_wards WHERE active=true ORDER BY id LIMIT 1");
        return safe(w,"name")+" / "+safe(w,"zone");
    }

    /**
     * Repairs legacy/demo complaints created by older builds. Routing is recomputed
     * from the original complaint text on the server so stale client/old-build
     * classifications cannot keep a complaint in the wrong department.
     */
    private void repairRoutingAndAssignments() {
        try {
            List<Map<String,Object>> gs=db.queryForList(
                "SELECT id,description,category,pincode,ward,detected_language,assigned_department,assigned_worker_id,assigned_worker_name,status,user_id,urgency FROM sp_grievances ORDER BY created_at ASC");
            for(Map<String,Object> g:gs){
                String id=safe(g,"id");
                String text=safe(g,"description").trim();
                if(text.isBlank()) continue;

                String oldDep=safe(g,"assigned_department");
                String oldWard=safe(g,"ward");
                Map<String,Object> analysis=triageDataWithoutAi(text, oldWard);
                String category=safe(analysis,"suggestedCategory","OTHER");
                String dep=DEPARTMENT.getOrDefault(category,DEPARTMENT.get("OTHER"));

                String ward=oldWard;
                Map<String,Object> loc=wardForPincode(safe(g,"pincode"));
                if(loc!=null) ward=safe(loc,"ward")+" / "+safe(loc,"zone");

                String detectedLanguage=detectLanguage(text);
                double languageConfidence=detectLanguageConfidence(text,detectedLanguage);
                String oldLanguage=safe(g,"detected_language");
                boolean languageChanged=!Objects.equals(detectedLanguage,oldLanguage);
                boolean routingChanged=!Objects.equals(category,safe(g,"category"))
                    || !Objects.equals(dep,oldDep)
                    || !Objects.equals(ward,oldWard);

                if(routingChanged || languageChanged){
                    int repairedSla=resolveSlaHours(category,safe(g,"urgency","MEDIUM"));
                    db.update("UPDATE sp_grievances SET category=?,assigned_department=?,ward=?,detected_language=?,language_confidence=?,english_translation=?,sla_hours=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        category,dep,ward,detectedLanguage,languageConfidence,
                        "en".equals(detectedLanguage) ? text : null,repairedSla,id);
                }

                // If a legacy complaint was routed to the wrong department, remove the
                // stale worker assignment and route it again using the corrected department.
                if(routingChanged && !Set.of("RESOLVED","COMPLETED","CLOSED").contains(safe(g,"status"))){
                    db.update("UPDATE sp_grievances SET assigned_worker_id=NULL,assigned_worker_name=NULL,status='SUBMITTED',updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
                    autoAssignFirstWorker(id,dep,ward,Map.of("id","SYSTEM"));
                } else if(!Set.of("RESOLVED","COMPLETED","CLOSED").contains(safe(g,"status")) && !notBlank(safe(g,"assigned_worker_id"))){
                    autoAssignFirstWorker(id,dep,ward,Map.of("id","SYSTEM"));
                }
            }
        } catch(Exception ignored) {}
    }

    private int resolveSlaHours(String category,String urgency) {
        int base=CATEGORY_SLA.getOrDefault(category,72);
        try {
            String department=DEPARTMENT.getOrDefault(category,DEPARTMENT.get("OTHER"));
            Integer configured=db.queryForObject("SELECT sla_hours FROM sp_departments WHERE name=? AND active=true LIMIT 1",Integer.class,department);
            if(configured!=null && configured>0) base=configured;
        } catch(Exception ignored) {}
        if("CRITICAL".equals(urgency)) return Math.min(base,12);
        if("HIGH".equals(urgency)) return Math.min(base,24);
        return base;
    }

    private Map<String,Object> triageDataWithoutAi(String text,String ward){
        String original=safe(text).trim();
        String lower=original.toLowerCase(Locale.ROOT);
        String language=detectLanguage(original);
        String category=detectCategory(lower);
        String urgency=detectUrgency(lower);
        int sla=resolveSlaHours(category,urgency);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("detectedLanguage",language);
        out.put("languageConfidence",detectLanguageConfidence(original, language));
        out.put("suggestedCategory",category);
        int matched=category.equals("OTHER")?0:categorySignalCount(lower,category);
        out.put("categoryConfidence",category.equals("OTHER")?0.61:Math.min(0.98,0.78+matched*0.06));
        out.put("urgency",urgency);
        out.put("slaHours",sla);
        return out;
    }

    private void repairUnassignedComplaints() {
        repairRoutingAndAssignments();
    }

    private void seedWelcomeNotifications() {
        try {
            for (Map<String,Object> row : db.queryForList("SELECT id,role FROM sp_users WHERE blocked=false")) {
                String id=safe(row,"id");
                if (count("SELECT COUNT(*) FROM sp_notifications WHERE user_id=?",id)==0) {
                    String title="SamadhanPoint workflow ready";
                    String message="Live notifications are enabled for complaints, routing, worker assignment, SLA, resolution and appeals.";
                    notifyUser(id,title,message,"SYSTEM");
                }
            }
        } catch(Exception ignored) {}
    }

    private void removeLegacyDemoAccounts() {
        try {
            db.update("UPDATE sp_users SET blocked=true WHERE id IN ('HEAD-WATER','HEAD-ROADS','HEAD-WASTE','HEAD-LIGHT','HEAD-HEALTH','HEAD-TRANSPORT','HEAD-SANITATION','HEAD-OTHER','WORK-WATER','WORK-ROAD','WORK-ELECTRIC','WORK-WASTE','WORK-HEALTH','WORK-TRANSPORT','WORK-SANITATION','WORK-OTHER','AUDIT-1','CIT-DEMO')");
        } catch (Exception ignored) { }
    }

    private void seedUser(String id, String username, String email, String password, String name, String phone, String role, String department, String ward, String language) {
        try {
            db.update("INSERT INTO sp_users(id,username,email,password_value,full_name,phone,role,department,ward_jurisdiction,preferred_language,blocked) VALUES(?,?,?,?,?,?,?,?,?,?,false) ON CONFLICT (username) DO NOTHING",
                id, username, email, hash(password), name, phone, role, department, ward, language);
        } catch (Exception ignored) { }
    }

    public Map<String,Object> reverseGeocode(double lat, double lon) {
        try {
            String url="https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat="+lat+"&lon="+lon+"&zoom=18&addressdetails=1";
            HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","SamadhanPoint/2.0 academic municipal grievance demo").timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> r=httpClient.send(req,HttpResponse.BodyHandlers.ofString());
            if(r.statusCode()<200||r.statusCode()>=300)return Map.of();
            Map<String,Object> root=mapper.readValue(r.body(),new TypeReference<Map<String,Object>>(){});
            Object ao=root.get("address"); if(!(ao instanceof Map<?,?> raw))return Map.of();
            Map<String,Object> a=new LinkedHashMap<>(); raw.forEach((k,v)->a.put(String.valueOf(k),v));
            String postcode=String.valueOf(a.getOrDefault("postcode",""));
            String road=String.valueOf(a.getOrDefault("road",a.getOrDefault("suburb","")));
            Map<String,Object> out=new LinkedHashMap<>(); out.put("pincode",postcode); out.put("address",road);
            Map<String,Object> ward=wardForPincode(postcode); if(ward!=null){out.putAll(ward);out.put("resolved",true);} else out.put("resolved",false);
            return out;
        }catch(Exception e){return Map.of();}
    }

    public Map<String, Object> wardForPincode(String pincode) {
        if (pincode == null || pincode.isBlank()) return null;
        List<Map<String, Object>> rows = db.queryForList("SELECT pincode,ward,zone,locality FROM sp_pincode_wards WHERE pincode=?", pincode.trim());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> triage(String text, String ward) {
        requestCount++; aiCallCount++;
        long started = System.nanoTime();
        Map<String, Object> data = triageData(text, ward);
        data.put("latencyMs", Math.max(1, (System.nanoTime() - started) / 1_000_000));
        return Map.of("success", true, "data", data);
    }

    public Map<String, Object> triageData(String text, String ward) {
        String original = safe(text).trim();
        String lower = original.toLowerCase(Locale.ROOT);
        String language = detectLanguage(original);
        String category = detectCategory(lower);
        String urgency = detectUrgency(lower);
        int sla = resolveSlaHours(category, urgency);
        List<Map<String, Object>> duplicates = duplicates(original, category);
        double breach = urgency.equals("CRITICAL") ? 0.68 : urgency.equals("HIGH") ? 0.32 : urgency.equals("LOW") ? 0.06 : 0.12;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("detectedLanguage", language);
        out.put("languageName", LANGUAGE_NAMES.getOrDefault(language, "English"));
        out.put("languageConfidence", language.equals("en") ? 0.96 : 0.92);
        out.put("suggestedCategory", category);
        int matchedSignals = category.equals("OTHER") ? 0 : categorySignalCount(lower, category);
        out.put("categoryConfidence", category.equals("OTHER") ? 0.61 : Math.min(0.98, 0.78 + matchedSignals * 0.06));
        out.put("urgency", urgency);
        out.put("assignedDepartment", DEPARTMENT.getOrDefault(category, DEPARTMENT.get("OTHER")));
        out.put("slaHours", sla);
        out.put("predictedBreachProbability", breach);
        out.put("potentialDuplicates", duplicates);
        out.put("englishTranslation", "en".equals(language) ? original : null);
        out.put("routingWard", safe(ward, defaultWard()));
        out.put("humanReviewRequired", ((Number) out.get("categoryConfidence")).doubleValue() < 0.75 || duplicates.stream().anyMatch(d -> ((Number) d.get("similarityScore")).doubleValue() >= 0.78));
        out.put("modelVersion", "samadhanpoint-local-triage-v3-rule-guard");
        // Groq is an assistive intelligence layer. Java rules remain authoritative for
        // security, ward mapping, department routing and SLA policy. When configured,
        // Groq enriches language, translation, category, priority and human-review signals.
        Map<String,Object> ai = openAiAssist(original, language, category);
        if (ai != null) {
            String aiLanguage = safe(ai, "language").toLowerCase(Locale.ROOT);
            if (Set.of("en","hi","mr").contains(aiLanguage)) {
                double aiLangConf = number(ai, "languageConfidence", out.get("languageConfidence") instanceof Number n ? n.doubleValue() : 0.92);
                out.put("aiDetectedLanguage", aiLanguage);
                out.put("aiLanguageConfidence", aiLangConf);
                // Never let a low-quality AI guess override a strong server-side
                // English signal. This prevents clear English text from becoming
                // Hindi/Marathi merely because the model guessed incorrectly.
                boolean strongEnglish = "en".equals(language) && Pattern.compile("^[\\p{ASCII}\\p{Punct}\\p{Space}]+$").matcher(original).matches();
                if (aiLangConf >= 0.70 && (!strongEnglish || "en".equals(aiLanguage))) {
                    language = aiLanguage;
                    out.put("detectedLanguage", language);
                    out.put("languageName", LANGUAGE_NAMES.getOrDefault(language, "English"));
                    out.put("languageConfidence", aiLangConf);
                }
            }
            String aiCategory = safe(ai, "category");
            Set<String> validCategories = Set.of("ROADS_POTHOLES","WATER_SUPPLY","WASTE_MANAGEMENT","ELECTRICITY_STREETLIGHTS","PUBLIC_HEALTH","PUBLIC_TRANSPORT","URBAN_SANITATION","OTHER");
            if (validCategories.contains(aiCategory)) {
                double aiCatConf = number(ai, "categoryConfidence", number(out, "categoryConfidence", 0.61));
                out.put("aiCategoryConfidence", aiCatConf);
                // Explicit server-side rule signals take precedence over AI guesses.
                // AI may resolve ambiguous OTHER cases, but never overrides a strong rule guard.
                if ("OTHER".equals(category) && aiCatConf >= 0.70) {
                    category = aiCategory;
                    out.put("suggestedCategory", category);
                    out.put("categoryConfidence", aiCatConf);
                    out.put("assignedDepartment", DEPARTMENT.getOrDefault(category, DEPARTMENT.get("OTHER")));
                }
            }
            String aiPriority = safe(ai, "priority").toUpperCase(Locale.ROOT);
            if (Set.of("LOW","MEDIUM","HIGH","CRITICAL").contains(aiPriority)) {
                double aiPriorityConf = number(ai, "priorityConfidence", 0.70);
                out.put("aiPriority", aiPriority);
                out.put("aiPriorityConfidence", aiPriorityConf);
                // AI priority is applied only when reasonably confident, and a
                // stronger server-side urgency can never be downgraded.
                String localPriority = safe(out, "urgency", "MEDIUM").toUpperCase(Locale.ROOT);
                String finalPriority = priorityRank(aiPriority) >= priorityRank(localPriority) ? aiPriority : localPriority;
                if (aiPriorityConf >= 0.70 || priorityRank(aiPriority) > priorityRank(localPriority)) {
                    out.put("urgency", finalPriority);
                    out.put("slaHours", resolveSlaHours(safe(out, "suggestedCategory", category), finalPriority));
                    out.put("predictedBreachProbability", finalPriority.equals("CRITICAL") ? 0.68 : finalPriority.equals("HIGH") ? 0.32 : finalPriority.equals("LOW") ? 0.06 : 0.12);
                }
            }
            String translation = safe(ai, "englishTranslation");
            if (!translation.isBlank() && !"en".equals(language)) out.put("englishTranslation", translation);
            out.put("aiHumanReviewRequired", Boolean.parseBoolean(safe(ai, "humanReviewRequired", "false")));
            out.put("aiProvider", "Groq");
            out.put("aiModel", groqModel);
            out.put("aiIntegration", "Responses API");
        } else {
            if (!"en".equals(language)) out.put("englishTranslation", fallbackEnglishTranslation(original, language));
            out.put("aiProvider", "Local rule engine");
            out.put("aiIntegration", "Fallback");
        }
        out.put("humanReviewRequired", Boolean.TRUE.equals(out.get("humanReviewRequired")) || Boolean.TRUE.equals(out.get("aiHumanReviewRequired")));
        return out;
    }

    private String fallbackEnglishTranslation(String text, String language) {
        if ("en".equals(language) || text == null || text.isBlank()) return text;
        String t=text;
        String[][] mr={
            {"आमच्या परिसरातील","in our area"},{"मुख्य रस्त्यावरील","on the main road"},{"अनेक पथदिवे","many street lights"},{"गेल्या काही दिवसांपासून बंद आहेत","have been out of service for the past few days"},{"रात्रीच्या वेळी","at night"},{"रस्ता पूर्णपणे अंधारात राहतो","the road remains completely dark"},{"नागरिकांना ये-जा करताना मोठ्या अडचणींचा सामना करावा लागतो","citizens face great difficulty while travelling"},{"कृपया बंद असलेले पथदिवे तातडीने दुरुस्त करून","please repair the non-working street lights immediately and"},{"परिसरात योग्य प्रकाशाची व्यवस्था करावी","ensure proper lighting in the area"},
            {"आमच्या परिसरात","in our area"},{"गेल्या चार दिवसांपासून","for the past four days"},{"कचरा उचललेला नाही","garbage has not been collected"},{"दुर्गंधी येत आहे","there is a foul smell"},{"कृपया लवकरात लवकर","please as soon as possible"}
        };
        String[][] hi={{"हमारे इलाके में","in our area"},{"कई दिनों से","for several days"},{"सफाई नहीं हुई है","cleaning has not been done"},{"कृपया जल्द से जल्द","please as soon as possible"},{"कचरा नहीं उठाया गया है","garbage has not been collected"},{"बहुत गंदगी है","there is a lot of dirt"},{"पानी की उचित व्यवस्था नहीं है","there is no proper water arrangement"}};
        for(String[] pair: "mr".equals(language)?mr:hi) t=t.replace(pair[0],pair[1]);
        return t;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> openAiAssist(String text, String language, String localCategory) {
        if (groqKey.isBlank() || text == null || text.isBlank()) return null;
        long aiStarted=System.nanoTime();
        try {
            String prompt = "You are the SamadhanPoint municipal triage AI. Supported complaint languages are English, Hindi and Marathi. " +
                    "Return ONLY valid JSON with these keys: language, languageConfidence, category, categoryConfidence, " +
                    "priority, priorityConfidence, englishTranslation, humanReviewRequired. " +
                    "Allowed category values: ROADS_POTHOLES, WATER_SUPPLY, WASTE_MANAGEMENT, ELECTRICITY_STREETLIGHTS, PUBLIC_HEALTH, PUBLIC_TRANSPORT, URBAN_SANITATION, OTHER. " +
                    "Allowed priority values: LOW, MEDIUM, HIGH, CRITICAL. " +
                    "Detect language from the actual text. Translate Hindi/Marathi into natural English. " +
                    "Do not invent facts. Recommend humanReviewRequired=true when the complaint is ambiguous, conflicting, unsafe, or confidence is below 0.70. " +
                    "Important routing guard: street-light/lamp/electric-light complaints are ELECTRICITY_STREETLIGHTS; pothole/road damage is ROADS_POTHOLES; water service/leakage is WATER_SUPPLY; public toilet/cleanliness is PUBLIC_HEALTH. " +
                    "Local preliminary category is " + "OTHER" + ". Complaint: " + text;
            Map<String,Object> req = new LinkedHashMap<>();
            req.put("model", groqModel);
            req.put("input", prompt);
            Map<String,Object> schema = new LinkedHashMap<>();
            Map<String,Object> properties = new LinkedHashMap<>();
            properties.put("language", Map.of("type", "string"));
            properties.put("languageConfidence", Map.of("type", "number"));
            properties.put("category", Map.of("type", "string"));
            properties.put("categoryConfidence", Map.of("type", "number"));
            properties.put("priority", Map.of("type", "string"));
            properties.put("priorityConfidence", Map.of("type", "number"));
            properties.put("englishTranslation", Map.of("type", "string"));
            properties.put("humanReviewRequired", Map.of("type", "boolean"));
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", List.of("language","languageConfidence","category","categoryConfidence","priority","priorityConfidence","englishTranslation","humanReviewRequired"));
            schema.put("additionalProperties", false);
            Map<String,Object> format = new LinkedHashMap<>();
            format.put("type", "json_schema");
            format.put("name", "samadhanpoint_triage");
            format.put("schema", schema);
            Map<String,Object> textFormat = new LinkedHashMap<>();
            textFormat.put("format", format);
            req.put("text", textFormat);
            String json = mapper.writeValueAsString(req);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/responses"))
                    .header("Authorization", "Bearer " + groqKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) { aiFailureCount++; lastAiLatencyMs=Math.max(1,(System.nanoTime()-aiStarted)/1_000_000); return null; }
            Map<String,Object> root = mapper.readValue(response.body(), new TypeReference<Map<String,Object>>() {});
            String outText = findOutputText(root);
            if (outText == null || outText.isBlank()) { aiFailureCount++; lastAiLatencyMs=Math.max(1,(System.nanoTime()-aiStarted)/1_000_000); return null; }
            int a=outText.indexOf('{'), b=outText.lastIndexOf('}');
            if(a<0||b<=a) { aiFailureCount++; lastAiLatencyMs=Math.max(1,(System.nanoTime()-aiStarted)/1_000_000); return null; }
            Map<String,Object> parsed=mapper.readValue(outText.substring(a,b+1), new TypeReference<Map<String,Object>>() {});
            aiSuccessCount++; lastAiLatencyMs=Math.max(1,(System.nanoTime()-aiStarted)/1_000_000);
            return parsed;
        } catch (Exception ignored) { aiFailureCount++; lastAiLatencyMs=Math.max(1,(System.nanoTime()-aiStarted)/1_000_000); return null; }
    }

    private int priorityRank(String priority) {
        return switch (safe(priority).toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private String findOutputText(Object node) {
        if (node instanceof Map<?,?> m) {
            Object type=m.get("type"); Object text=m.get("text");
            if ("output_text".equals(String.valueOf(type)) && text != null) return String.valueOf(text);
            for(Object v:m.values()){String r=findOutputText(v); if(r!=null)return r;}
        } else if(node instanceof Iterable<?> it) { for(Object v:it){String r=findOutputText(v); if(r!=null)return r;} }
        return null;
    }

    private double detectLanguageConfidence(String text, String language) {
        if (text == null || text.isBlank()) return 0.50;
        int totalLetters = 0, latinLetters = 0, devanagari = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                totalLetters++;
                if (c >= '\u0900' && c <= '\u097F') devanagari++;
                else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latinLetters++;
            }
        }
        String l=text.toLowerCase(Locale.ROOT);
        if ("en".equals(language)) {
            double ratio=totalLetters==0?0:(double)latinLetters/totalLetters;
            int common=0;
            for(String w: new String[]{"the","there","is","are","please","road","water","garbage","street","light","complaint","near","from","with"}) if (has(l,w)) common++;
            return round4(Math.min(0.99, 0.70 + ratio*0.20 + Math.min(0.09, common*0.015)));
        }
        int hiSignals=0,mrSignals=0;
        if(has(l,"मुझे","मेरी","मेरे","पिछले","पिछली","है","हैं","नहीं","समस्या","समाधान","सड़क","पानी","कचरा","शिकायत","करना","करें","बहुत")) hiSignals+=2;
        if(has(l,"आमच्या","आम्ही","आम्हाला","माझ्या","माझं","तक्रार","कृपया","करा","कुठे","मुळे","झाले","झालं","आहे")) mrSignals+=2;
        if(has(l,"mujhe","meri","mere","mein","pichhle","pichli","raha hai","rahi hai","gaya hai","bahut","badbu","kripya","shikayat","samadhan","karein","karna")) hiSignals++;
        if(has(l,"aamchya","aamhi","aamhala","majhya","majha","mala","aahe","takrar","krupaya","kara","kuthe","mule","zale","zali")) mrSignals++;
        int marker=Math.max("mr".equals(language)?mrSignals:hiSignals, 0);
        double base=devanagari>0?0.72:0.65;
        double confidence=base+Math.min(0.25, marker*0.035);
        if ("mr".equals(language) && mrSignals>hiSignals) confidence+=0.02;
        if ("hi".equals(language) && hiSignals>mrSignals) confidence+=0.02;
        return round4(Math.min(0.98, confidence));
    }

    private double round4(double x){ return Math.round(x*10000.0)/10000.0; }

    private String detectLanguage(String text) {
        if (text == null || text.isBlank()) return "en";
        String l = text.toLowerCase(Locale.ROOT);
        // Devanagari is shared by Hindi and Marathi. Use distinctive Marathi markers
        // first; shared words such as पानी/पाणी, रस्ता and नाही must not decide the language.
        if (Pattern.compile("[\\u0900-\\u097F]").matcher(text).find()) {
            if (has(l, "आमच्या", "आम्ही", "आम्हाला", "माझ्या", "माझं", "आहे", "तक्रार", "कृपया", "करा", "होत आहे", "कुठे", "मुळे", "झाले", "झालं")) return "mr";
            if (has(l, "मुझे", "मेरी", "मेरे", "पिछले", "पिछली", "है", "हैं", "नहीं", "कृपया", "समस्या", "समाधान", "सड़क", "पानी", "कचरा", "शिकायत", "करना", "करें", "बहुत")) return "hi";
            // When the script alone is available, Hindi is the safer default because
            // ordinary Devanagari civic vocabulary is highly overlapping.
            return "hi";
        }

        // Romanized Hindi and Marathi need different handling. Shared words such as
        // paani/pani, rasta, light and nahi occur in both languages, so never classify
        // Marathi from those shared words alone.
        int mr = 0, hi = 0;
        if (has(l, "aamchya", "aamhi", "aamhala", "majhya", "majha", "maza", "mala", "aahe", "takrar", "krupaya", "kara", "kuthe", "mule", "zale", "zali", "hot aahe")) mr += 3;
        if (has(l, "mujhe", "meri", "mere", "mein", "pichhle", "pichli", "din se", "raha hai", "rahi hai", "gaya hai", "bahut", "badbu", "kripya", "shikayat", "samadhan", "karein", "karna", "jaldi", "ho gaya", "ho rahi")) hi += 3;
        // Common Roman Hindi spellings. These are only supporting signals, not Marathi signals.
        if (has(l, "hai", "hain", "nahi", "paani", "pani", "sadak", "kachra", "gaddha", "gaddhe", "mujhe", "kripya")) hi++;
        if (mr > hi && mr >= 3) return "mr";
        if (hi > 0) return "hi";
        return "en";
    }

    private String detectCategory(String l) {
        // Server-authoritative routing. Specific water complaints are checked first so
        // pipeline/leakage complaints cannot be misrouted because they also mention a road,
        // street or flooding.
        if (has(l, "water leakage", "water leak", "pipeline leakage", "pipe leakage", "burst pipe",
                "water supply", "water shortage", "no water", "water connection", "water tank",
                "paani supply", "paani leakage", "paani leak", "paani nahi", "पानी की सप्लाई", "पाणीपुरवठा", "पाणी गळती")) return "WATER_SUPPLY";
        // Explicit precedence for civic routing. Specific issue terms must win over
        // generic location words such as road/street.
        if (has(l, "street light", "streetlight", "street lights", "light pole", "lamp post",
                "lights not working", "light not working", "lights are off", "light is off",
                "electric pole", "electric wire", "power line", "पथदिवे", "पथदिवा",
                "स्ट्रीट लाईट", "स्ट्रीट लाइट", "दिवे बंद", "दिवा बंद", "लाईट बंद",
                "लाइट बंद", "प्रकाश नाही", "अंधार", "बिजली", "लाइट", "लाईट", "वीज")) {
            return "ELECTRICITY_STREETLIGHTS";
        }
        if (has(l, "garbage collection", "garbage", "waste", "trash", "kooda", "kachra",
                "कचरा", "कूड़ा", "dustbin", "uncollected waste", "कचरा नहीं उठाया", "कचरा जमा")) return "WASTE_MANAGEMENT";
        if (has(l, "deep pothole", "pothole", "road damage", "damaged road", "broken road",
                "road repair", "footpath", "pavement", "gaddha", "gaddhe", "sadak", "रस्ता",
                "सड़क", "खड्डा", "गड्ढा")) return "ROADS_POTHOLES";
        if (has(l, "no water", "water supply", "water shortage", "water leakage", "pipeline",
                "pipe leakage", "tap not working", "paani supply", "पानी की सप्लाई", "पाणीपुरवठा")) return "WATER_SUPPLY";
        if (has(l, "public toilet", "public washroom", "toilet", "washroom", "sanitation", "cleanliness",
                "cleaning", "bad smell", "foul smell", "mosquito", "dengue", "malaria", "health risk",
                "शौचालय", "सार्वजनिक शौचालय", "सफाई", "स्वच्छता", "गंदगी", "गंदा", "दुर्गंध", "बदबू",
                "मच्छर", "डेंगू", "आरोग्य", "रुग्णालय")) return "PUBLIC_HEALTH";
        if (has(l, "drainage", "drain", "sewer", "manhole", "gutter", "nala", "नाला", "गटार",
                "waterlogging", "flooded", "sewage", "सीवेज")) return "URBAN_SANITATION";
        if (has(l, "bus", "transport", "traffic", "parking", "bus stop", "बस", "वाहतूक", "ट्रैफिक")) return "PUBLIC_TRANSPORT";
        return "OTHER";
    }

    private int score(String text, int phraseWeight, String... phrases) {
        int n=0; for(String p:phrases){if(text.contains(p)) n += p.contains(" ") ? phraseWeight : Math.max(1, phraseWeight-2); } return n;
    }

    private int categorySignalCount(String text, String category) {
        return switch (category) {
            case "ROADS_POTHOLES" -> score(text, 1, "pothole", "road", "gaddha", "sadak", "रस्ता", "सड़क", "खड्डा", "गड्ढा");
            case "WATER_SUPPLY" -> score(text, 1, "water", "paani", "पानी", "पाणी", "pipeline", "leakage");
            case "WASTE_MANAGEMENT" -> score(text, 1, "garbage", "waste", "trash", "kachra", "कचरा");
            case "ELECTRICITY_STREETLIGHTS" -> score(text, 1, "light", "electric", "wire", "बिजली", "लाइट", "वीज");
            case "PUBLIC_HEALTH" -> score(text, 1, "hospital", "health", "public toilet", "public washroom", "toilet", "washroom", "sanitation", "cleanliness", "cleaning", "bad smell", "foul smell", "dengue", "malaria", "mosquito", "शौचालय", "सफाई", "स्वच्छता", "गंदगी", "दुर्गंध", "बदबू", "मच्छर", "आरोग्य");
            case "URBAN_SANITATION" -> score(text, 1, "drain", "sewer", "manhole", "nala", "नाला", "गटार", "sewage");
            case "PUBLIC_TRANSPORT" -> score(text, 1, "bus", "transport", "traffic", "parking", "बस", "वाहतूक", "ट्रैफिक");
            default -> 0;
        };
    }

    private String detectUrgency(String l) {
        if (has(l, "emergency", "critical", "life threat", "open manhole", "sparking", "fire", "collapse", "accident", "danger", "खतरा", "तुरंत", "आणीबाणी", "धोका")) return "CRITICAL";
        if (has(l, "urgent", "broken", "overflow", "deep pothole", "school", "dengue", "contamination", "dark street", "खराब", "लीकेज", "गंदगी", "दुर्घटना")) return "HIGH";
        if (has(l, "minor", "inquiry", "feedback", "painting", "pruning", "माहिती", "चौकशी")) return "LOW";
        return "MEDIUM";
    }

    private boolean sameScope(String a,String b){
        return normalizeScope(a).equalsIgnoreCase(normalizeScope(b));
    }

    private String normalizeScope(String value){
        return safe(value).trim().replaceAll("\\s*/\\s*"," / ").replaceAll("\\s+"," ");
    }

    private boolean has(String text, String... words) { for (String word : words) if (text.contains(word)) return true; return false; }

    private List<Map<String, Object>> duplicates(String text, String category) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (text.isBlank()) return result;
        try {
            for (Map<String, Object> row : db.queryForList("SELECT id,tracking_code,description,category,status FROM sp_grievances ORDER BY created_at DESC LIMIT 150")) {
                double score = similarity(text, String.valueOf(row.get("description")));
                boolean sameCategory = Objects.equals(category, String.valueOf(row.get("category")));
                if (score >= 0.42 || (sameCategory && score >= 0.34)) {
                    result.add(Map.of("grievanceId", row.get("id"), "trackingCode", row.get("tracking_code"), "similarityScore", Math.round(score * 100.0) / 100.0, "summary", String.valueOf(row.get("description")), "status", row.get("status")));
                }
            }
        } catch (Exception ignored) { }
        result.sort((a, b) -> Double.compare(((Number) b.get("similarityScore")).doubleValue(), ((Number) a.get("similarityScore")).doubleValue()));
        return result.size() > 3 ? new ArrayList<>(result.subList(0, 3)) : result;
    }

    private double similarity(String a, String b) {
        Set<String> A = tokenSet(a), B = tokenSet(b);
        if (A.isEmpty() || B.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(A); intersection.retainAll(B);
        Set<String> union = new HashSet<>(A); union.addAll(B);
        return (double) intersection.size() / union.size();
    }

    private Set<String> tokenSet(String value) {
        Set<String> set = new HashSet<>(Arrays.asList(safe(value).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        set.remove("");
        return set;
    }

    @Transactional
    public Map<String, Object> createGrievance(Map<String, Object> body, Map<String, Object> user) {
        requestCount++;
        if (!isRole(user, "CITIZEN")) return error("Only citizens can submit complaints.");
        String text = safe(body, "originalText", safe(body, "description")).trim();
        Map<String,Object> gps = body.get("location") instanceof Map<?,?> m ? toStringMap(m) : Map.of();
        Double lat = numberObject(gps.get("latitude"));
        Double lon = numberObject(gps.get("longitude"));
        Double acc = numberObject(gps.get("accuracyMeters"));

        String pincode = safe(body, "pincode").trim();
        Map<String, Object> loc = null;
        // GPS is authoritative when supplied: reverse-geocode it and derive pincode/ward.
        if (lat != null && lon != null) {
            Map<String,Object> resolved = reverseGeocode(lat,lon);
            if (Boolean.TRUE.equals(resolved.get("resolved"))) {
                pincode = safe(resolved,"pincode",pincode);
                loc = wardForPincode(pincode);
            } else if (pincode.isBlank()) {
                return error("GPS was captured, but the location could not be mapped to a supported municipal ward. Please retry GPS.");
            }
        }
        if (loc == null) loc = wardForPincode(pincode);
        if (pincode.isBlank() || loc == null) return error("Enter a supported Mumbai pincode or capture GPS so the system can determine the correct ward automatically.");
        String ward = loc.get("ward") + " / " + loc.get("zone");
        // Never trust a client-supplied category/language for routing. Recompute the decision on the server.
        List<Map<String,Object>> enrichedAttachments = normalizeAttachments(body.get("attachments"));

        Map<String, Object> analysis = triageData(text, ward);
        String serverLanguage = safe(analysis, "detectedLanguage", "en");
        String category = safe(analysis, "suggestedCategory", "OTHER");
        // Strong civic issue keywords are authoritative for routing. This prevents an
        // AI guess or stale client analysis from routing a clear water/road/waste/etc.
        // complaint to the wrong department.
        String ruleCategory = detectCategory(text.toLowerCase(Locale.ROOT));
        if (!"OTHER".equals(ruleCategory)) {
            category = ruleCategory;
            analysis.put("suggestedCategory", category);
            analysis.put("assignedDepartment", DEPARTMENT.getOrDefault(category, DEPARTMENT.get("OTHER")));
        }
        String urgency = safe(analysis, "urgency", "MEDIUM");
        int sla = resolveSlaHours(category, urgency);
        String id = UUID.randomUUID().toString();
        String tracking = "MUM-" + Year.now().getValue() + "-" + String.format("%06d", nextSequence());
        Map<String,Object> persistedData = new LinkedHashMap<>();
        persistedData.put("location", body.get("location") == null ? Map.of() : body.get("location"));
        persistedData.put("attachments", enrichedAttachments);
        persistedData.put("analysis", analysis);
        String data = jsonSafe(persistedData);
        db.update("INSERT INTO sp_grievances(id,tracking_code,user_id,citizen_name,contact_email,ward,city,pincode,street_address,locality,landmark,category,description,detected_language,language_confidence,english_translation,category_confidence,assigned_department,urgency,priority,status,sla_hours,predicted_breach_probability,is_emergency,latitude,longitude,location_accuracy,data) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",
            id, tracking, user.get("id"), safe(user, "name", "Citizen"), safe(user, "email"), ward, "Mumbai", pincode,
            safe(body, "streetAddress", safe(loc, "locality")), safe(body, "locality", safe(loc, "locality")), safe(body, "landmark"), category, text,
            safe(analysis, "detectedLanguage", "en"), number(analysis, "languageConfidence", 0.92), safe(analysis, "englishTranslation", text),
            number(analysis, "categoryConfidence", 0.93), DEPARTMENT.getOrDefault(category, DEPARTMENT.get("OTHER")), urgency, urgency,
            "SUBMITTED", sla, number(analysis, "predictedBreachProbability", 0.12), Boolean.TRUE.equals(body.get("isEmergency")), lat, lon, acc, data);
        // Record the citizen submission as the first workflow event so the timeline
        // never jumps directly from creation to worker activity.
        db.update("INSERT INTO sp_complaint_updates(id,grievance_id,actor_id,status,note,evidence,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString(), id, user.get("id"), "SUBMITTED", "Complaint submitted by citizen", null);
        autoAssignFirstWorker(id, DEPARTMENT.getOrDefault(category, DEPARTMENT.get("OTHER")), ward, user);
        Object dupObj=analysis.get("potentialDuplicates");
        if(dupObj instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?,?> dm){
            Double ds=numberObject(dm.get("similarityScore"));
            if(ds!=null && ds>=0.78) db.update("UPDATE sp_grievances SET duplicate_of_id=?,similarity_score=? WHERE id=?", dm.get("grievanceId"), ds, id);
        }
        audit(user, id, "SUBMITTED", Map.of("trackingCode", tracking, "category", category, "language", safe(analysis, "detectedLanguage", "en"), "ward", ward));
        notifyDepartmentHeads(DEPARTMENT.getOrDefault(category, DEPARTMENT.get("OTHER")), ward, "New grievance routed", tracking + " has been routed to your department and ward for review.", "ROUTING");
        notifyRole("ADMIN", "New citizen grievance", tracking + " was submitted and routed to " + DEPARTMENT.getOrDefault(category, DEPARTMENT.get("OTHER")) + ".", "COMPLAINT");
        notifyRole("AUDITOR", "New grievance recorded", tracking + " was recorded with department " + DEPARTMENT.getOrDefault(category, DEPARTMENT.get("OTHER")) + ".", "AUDIT");
        notifyUser(safe(user, "id"), "Complaint submitted", tracking + " has been registered and routed to " + DEPARTMENT.getOrDefault(category, DEPARTMENT.get("OTHER")) + ".", "COMPLAINT");
        return Map.of("success", true, "data", getGrievance(id));
    }

    private void autoAssignFirstWorker(String grievanceId, String department, String ward, Map<String,Object> actor) {
        try {
            List<Map<String,Object>> workers=db.queryForList("SELECT * FROM sp_users WHERE role='WORKER' AND blocked=false AND LOWER(TRIM(department))=LOWER(TRIM(?)) AND LOWER(TRIM(ward_jurisdiction))=LOWER(TRIM(?)) ORDER BY full_name LIMIT 1", department, ward);
            if(workers.isEmpty()) return;
            Map<String,Object> w=workers.get(0);
            db.update("UPDATE sp_grievances SET assigned_worker_id=?,assigned_worker_name=?,status='ASSIGNED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND assigned_worker_id IS NULL",w.get("id"),w.get("full_name"),grievanceId);
            db.update("INSERT INTO sp_complaint_updates(id,grievance_id,actor_id,status,note,evidence,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), grievanceId, actor.get("id"), "ASSIGNED", "Field worker assigned automatically", null);
            audit(actor,grievanceId,"AUTO_ASSIGNED",Map.of("workerId",w.get("id"),"workerName",w.get("full_name"),"department",department,"ward",ward));
            notifyUser(safe(w,"id"),"New field task assigned", "A new complaint in " + department + " / " + ward + " has been assigned to you.", "TASK");
        } catch(Exception ignored) {}
    }

    private long nextSequence() {
        try { return db.queryForObject("SELECT COALESCE(MAX(CAST(SUBSTRING(tracking_code,10) AS BIGINT)),0)+1 FROM sp_grievances WHERE tracking_code LIKE 'MUM-%'", Long.class); }
        catch (Exception e) { return 1; }
    }

    public List<Map<String, Object>> listGrievances(Map<String, Object> user) {
        ensureSeeds();
        repairRoutingAndAssignments();
        if (user == null) return List.of();
        String role = safe(user, "role");
        StringBuilder sql = new StringBuilder("SELECT * FROM sp_grievances WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if ("CITIZEN".equals(role)) { sql.append(" AND user_id=?"); params.add(user.get("id")); }
        else if ("WORKER".equals(role)) { sql.append(" AND assigned_worker_id=?"); params.add(user.get("id")); }
        else if ("DEPARTMENT_HEAD".equals(role)) {
            sql.append(" AND LOWER(TRIM(assigned_department))=LOWER(TRIM(?)) AND LOWER(TRIM(ward))=LOWER(TRIM(?))");
            params.add(user.get("department"));
            params.add(user.get("wardJurisdiction"));
        }
        else if ("WARD_HEAD".equals(role)) { sql.append(" AND ward=?"); params.add(user.get("wardJurisdiction")); }
        sql.append(" ORDER BY created_at DESC");
        return grievanceRows(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> filtered(Map<String, Object> user, String department, String language, String ward, String status) {
        ensureSeeds();
        repairRoutingAndAssignments();
        if (!isAnyRole(user, "ADMIN", "AUDITOR")) return listGrievances(user);
        StringBuilder sql = new StringBuilder("SELECT * FROM sp_grievances WHERE 1=1");
        List<Object> p = new ArrayList<>();
        if (notBlank(department)) { sql.append(" AND assigned_department=?"); p.add(department); }
        if (notBlank(language)) { sql.append(" AND detected_language=?"); p.add(language); }
        if (notBlank(ward)) { sql.append(" AND ward LIKE ?"); p.add(ward + "%"); }
        if (notBlank(status)) { sql.append(" AND status=?"); p.add(status); }
        sql.append(" ORDER BY created_at DESC");
        return grievanceRows(sql.toString(), p.toArray());
    }

    private List<Map<String, Object>> grievanceRows(String sql, Object... params) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : db.queryForList(sql, params)) out.add(rowToGrievance(row));
        return out;
    }

    public Map<String, Object> getGrievance(String idOrTracking) {
        ensureSeeds();
        repairRoutingAndAssignments();
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM sp_grievances WHERE id=? OR tracking_code=?", idOrTracking, idOrTracking);
        return rows.isEmpty() ? null : rowToGrievance(rows.get(0));
    }

    public boolean canView(Map<String, Object> user, Map<String, Object> grievance) {
        if (user == null || grievance == null) return false;
        String role = safe(user, "role");
        if (isAnyRole(user, "ADMIN", "AUDITOR")) return true;
        if ("CITIZEN".equals(role)) return Objects.equals(safe(user, "id"), safe(grievance, "userId"));
        if ("WORKER".equals(role)) return Objects.equals(safe(user, "id"), safe(grievance, "assignedWorkerId"));
        if ("DEPARTMENT_HEAD".equals(role)) return sameScope(safe(user, "department"), safe(grievance, "assignedDepartment")) && sameScope(safe(user, "wardJurisdiction"), safe(grievance, "wardJurisdiction"));
        return false;
    }

    public List<Map<String, Object>> workersForGrievance(String id, Map<String, Object> user) {
        Map<String, Object> grievance = getGrievance(id);
        if (grievance == null || !canManage(user, grievance)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : db.queryForList("SELECT * FROM sp_users WHERE role='WORKER' AND blocked=false AND LOWER(TRIM(department))=LOWER(TRIM(?)) AND LOWER(TRIM(ward_jurisdiction))=LOWER(TRIM(?)) ORDER BY full_name", grievance.get("assignedDepartment"), grievance.get("wardJurisdiction"))) {
            out.add(userRow(row));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> assign(String id, String workerId, Map<String, Object> user) {
        Map<String, Object> grievance = getGrievance(id);
        if (grievance == null) return error("Grievance not found.");
        if (!canManage(user, grievance)) return error("You do not have permission to assign this complaint.");
        List<Map<String, Object>> workers = db.queryForList("SELECT * FROM sp_users WHERE id=? AND role='WORKER' AND blocked=false", workerId);
        if (workers.isEmpty()) return error("Worker not found or blocked.");
        Map<String, Object> worker = workers.get(0);
        if (!sameScope(safe(worker, "department"), safe(grievance, "assignedDepartment"))) return error("Worker belongs to a different department.");
        if (!sameScope(safe(worker, "ward_jurisdiction"), safe(grievance, "wardJurisdiction"))) return error("Worker belongs to a different ward.");
        db.update("UPDATE sp_grievances SET assigned_worker_id=?,assigned_worker_name=?,status='ASSIGNED',updated_at=CURRENT_TIMESTAMP WHERE id=?", workerId, worker.get("full_name"), id);
        audit(user, id, "ASSIGNED", Map.of("workerId", workerId, "workerName", worker.get("full_name"), "department", safe(grievance,"assignedDepartment"), "ward", safe(grievance,"wardJurisdiction"), "pincode", safe(grievance,"pincode")));
        notifyUser(workerId, "New field task assigned", safe(grievance, "trackingCode") + " has been assigned to you. Please start the field workflow.", "TASK");
        notifyUser(safe(grievance, "userId"), "Worker assigned", safe(grievance, "trackingCode") + " has been assigned to a field worker.", "TASK");
        notifyRole("AUDITOR", "Task assignment recorded", safe(grievance, "trackingCode") + " was assigned to " + safe(worker, "full_name") + ".", "AUDIT");
        return Map.of("success", true, "data", getGrievance(id));
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body, Map<String, Object> user) {
        Map<String, Object> grievance = getGrievance(id);
        if (grievance == null) return error("Grievance not found.");
        String role = safe(user, "role");
        if ("WORKER".equals(role) && !Objects.equals(safe(grievance, "assignedWorkerId"), safe(user, "id"))) return error("This task is not assigned to you.");
        if ("DEPARTMENT_HEAD".equals(role) && (!Objects.equals(safe(grievance, "assignedDepartment"), safe(user, "department")) || !Objects.equals(safe(grievance, "wardJurisdiction"), safe(user, "wardJurisdiction")))) return error("This complaint belongs to another department or ward.");
        if (!isAnyRole(user, "ADMIN", "DEPARTMENT_HEAD", "WORKER")) return error("You do not have permission to update this complaint.");
        String status = safe(body, "status", safe(grievance, "status", "SUBMITTED"));
        if (!Set.of("SUBMITTED", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "COMPLETED", "CLOSED", "REOPENED").contains(status)) return error("Invalid status.");
        if ("WORKER".equals(role) && "CLOSED".equals(status)) return error("Worker cannot close a complaint. Mark it RESOLVED and let the citizen verify the resolution.");
        String before = nullable(body, "beforeEvidence");
        String after = nullable(body, "afterEvidence");
        String notes = nullable(body, "resolutionNotes");
        db.update("UPDATE sp_grievances SET status=?,before_evidence=COALESCE(?,before_evidence),after_evidence=COALESCE(?,after_evidence),resolution_notes=COALESCE(?,resolution_notes),updated_at=CURRENT_TIMESTAMP,resolved_at=CASE WHEN ? IN ('RESOLVED','COMPLETED','CLOSED') THEN COALESCE(resolved_at,CURRENT_TIMESTAMP) ELSE resolved_at END,data=COALESCE(data,'{}'::jsonb)||?::jsonb WHERE id=?",
            status, before, after, notes, status, jsonSafe(body), id);
        db.update("INSERT INTO sp_complaint_updates(id,grievance_id,actor_id,status,note,evidence,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString(), id, user.get("id"), status, safe(body, "note", notes == null ? "Status updated" : notes), after);
        audit(user, id, "STATUS_UPDATED", Map.of("status", status, "department", safe(grievance,"assignedDepartment"), "ward", safe(grievance,"wardJurisdiction"), "pincode", safe(grievance,"pincode")));
        String trackingCode = safe(grievance, "trackingCode");
        String citizenId = safe(grievance, "userId");
        if (notBlank(citizenId)) notifyUser(citizenId, "Complaint status updated", trackingCode + " is now " + status.replace('_',' ') + ".", "STATUS");
        if ("RESOLVED".equals(status) || "COMPLETED".equals(status)) notifyUser(citizenId, "Resolution ready for verification", trackingCode + " has been resolved. Please verify the resolution or submit an appeal.", "VERIFICATION");
        notifyRole("AUDITOR", "Complaint workflow updated", trackingCode + " status changed to " + status.replace('_',' ') + ".", "AUDIT");
        if (notBlank(safe(grievance, "assignedWorkerId")) && !Objects.equals(safe(user, "id"), safe(grievance, "assignedWorkerId"))) notifyUser(safe(grievance, "assignedWorkerId"), "Task updated", trackingCode + " was updated to " + status.replace('_',' ') + ".", "TASK");
        return Map.of("success", true, "data", getGrievance(id));
    }

    private boolean canManage(Map<String, Object> user, Map<String, Object> grievance) {
        return isRole(user, "ADMIN") || (isRole(user, "DEPARTMENT_HEAD")
            && Objects.equals(safe(user, "department"), safe(grievance, "assignedDepartment"))
            && Objects.equals(safe(user, "wardJurisdiction"), safe(grievance, "wardJurisdiction")));
    }

    @Transactional
    private List<Map<String,Object>> normalizeAttachments(Object raw) {
        List<Map<String,Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list) {
            if (item instanceof Map<?,?> m) {
                Map<String,Object> x=new LinkedHashMap<>();
                m.forEach((k,v)->x.put(String.valueOf(k),v));
                out.add(x);
            }
        }
        return out;
    }

    
    
    
    
    
    
    @Transactional
    public Map<String, Object> deleteGrievance(String id, Map<String, Object> user) {
        if (!isRole(user, "ADMIN")) return error("Admin only.");
        Map<String, Object> grievance = getGrievance(id);
        if (grievance == null) return error("Grievance not found.");
        db.update("DELETE FROM sp_complaint_updates WHERE grievance_id=?", id);
        db.update("DELETE FROM sp_appeals WHERE grievance_id=?", id);
        db.update("DELETE FROM sp_grievances WHERE id=?", id);
        audit(user, id, "DELETED", Map.of("trackingCode", safe(grievance, "trackingCode")));
        return Map.of("success", true);
    }

    @Transactional
    public Map<String, Object> verifyResolution(String id, Map<String, Object> user) {
        Map<String, Object> grievance = getGrievance(id);
        if (grievance == null) return error("Complaint not found.");
        if (!isRole(user, "CITIZEN") || !Objects.equals(safe(grievance, "userId"), safe(user, "id"))) return error("Only the complaint owner can verify this resolution.");
        if (!Set.of("RESOLVED", "COMPLETED").contains(safe(grievance, "status"))) return error("Only a resolved complaint can be verified.");
        db.update("UPDATE sp_grievances SET status='CLOSED',updated_at=CURRENT_TIMESTAMP,resolved_at=COALESCE(resolved_at,CURRENT_TIMESTAMP) WHERE id=?", id);
        db.update("INSERT INTO sp_complaint_updates(id,grievance_id,actor_id,status,note,evidence,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), id, user.get("id"), "CLOSED", "Citizen verified the resolution", null);
        audit(user, id, "CITIZEN_VERIFIED", Map.of("verified", true));
        notifyRole("ADMIN", "Complaint verified", safe(grievance, "trackingCode") + " was verified and closed by the citizen.", "STATUS");
        notifyRole("AUDITOR", "Complaint closed", safe(grievance, "trackingCode") + " was verified and closed by the citizen.", "AUDIT");
        if (notBlank(safe(grievance, "assignedWorkerId"))) notifyUser(safe(grievance, "assignedWorkerId"), "Complaint verified", safe(grievance, "trackingCode") + " was verified and closed by the citizen.", "STATUS");
        return Map.of("success", true, "data", getGrievance(id));
    }

    public List<Map<String, Object>> timeline(String id, Map<String, Object> user) {
        Map<String, Object> grievance = getGrievance(id);
        if (grievance == null || !canView(user, grievance)) return List.of();
        List<Map<String,Object>> events = new ArrayList<>(db.queryForList("SELECT id,status,note,evidence,created_at FROM sp_complaint_updates WHERE grievance_id=? ORDER BY created_at ASC", id));
        // Backfill the initial workflow markers for complaints created by older builds
        // that did not persist SUBMITTED/ASSIGNED timeline rows.
        boolean hasSubmitted = events.stream().anyMatch(e -> "SUBMITTED".equals(safe(e, "status")));
        boolean hasAssigned = events.stream().anyMatch(e -> "ASSIGNED".equals(safe(e, "status")));
        if (!hasSubmitted) {
            Map<String,Object> submitted = new LinkedHashMap<>();
            submitted.put("id", "synthetic-submitted-" + id);
            submitted.put("status", "SUBMITTED");
            submitted.put("note", "Complaint submitted by citizen");
            submitted.put("evidence", null);
            submitted.put("created_at", grievance.get("createdAt"));
            events.add(0, submitted);
        }
        if (notBlank(safe(grievance, "assignedWorkerId")) && !hasAssigned) {
            Map<String,Object> assigned = new LinkedHashMap<>();
            assigned.put("id", "synthetic-assigned-" + id);
            assigned.put("status", "ASSIGNED");
            assigned.put("note", "Field worker assigned");
            assigned.put("evidence", null);
            assigned.put("created_at", grievance.get("updatedAt"));
            int insertAt = Math.min(1, events.size());
            events.add(insertAt, assigned);
        }
        return events;
    }

    @Transactional
    public Map<String, Object> createAppeal(String id, Map<String, Object> body, Map<String, Object> user) {
        Map<String, Object> grievance = getGrievance(id);
        if (grievance == null) return error("Complaint not found.");
        if (!isRole(user, "CITIZEN") || !Objects.equals(safe(grievance, "userId"), safe(user, "id"))) return error("You can appeal only your own complaint.");
        if (!Set.of("RESOLVED", "COMPLETED", "CLOSED").contains(safe(grievance, "status"))) return error("Appeal becomes available after resolution.");
        String reason = safe(body, "reason").trim();
        if (reason.isBlank()) return error("Appeal reason is required.");
        Integer activeAppeal=count("SELECT COUNT(*) FROM sp_appeals WHERE grievance_id=? AND status='SUBMITTED'",id);
        if(activeAppeal>0) return error("An appeal is already submitted for this complaint and is awaiting review.");
        String aid = UUID.randomUUID().toString();
        db.update("INSERT INTO sp_appeals(id,grievance_id,citizen_id,reason,evidence,status) VALUES(?,?,?,?,?,'SUBMITTED')", aid, id, user.get("id"), reason, nullable(body, "evidence"));
        db.update("UPDATE sp_grievances SET appeal_status='SUBMITTED',appeal_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", reason, id);
        audit(user, id, "APPEAL_SUBMITTED", Map.of("appealId", aid));
        notifyDepartmentHeads(safe(grievance, "assignedDepartment"), safe(grievance, "wardJurisdiction"), "New appeal requires review", safe(grievance, "trackingCode") + " has a citizen appeal waiting for human review.", "APPEAL");
        notifyRole("ADMIN", "New citizen appeal", safe(grievance, "trackingCode") + " has a new appeal requiring review.", "APPEAL");
        notifyRole("AUDITOR", "New appeal recorded", safe(grievance, "trackingCode") + " has a new appeal for review.", "AUDIT");
        return Map.of("success", true, "data", appeal(aid));
    }

    public List<Map<String, Object>> appeals(Map<String, Object> user) {
        if (user == null) return List.of();
        String role = safe(user, "role");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : db.queryForList("SELECT * FROM sp_appeals ORDER BY created_at DESC")) {
            if ("CITIZEN".equals(role) && !Objects.equals(row.get("citizen_id"), user.get("id"))) continue;
            if ("DEPARTMENT_HEAD".equals(role)) {
                Map<String, Object> g = getGrievance(String.valueOf(row.get("grievance_id")));
                if (g == null || !sameScope(safe(g, "assignedDepartment"), safe(user, "department")) || !sameScope(safe(g, "wardJurisdiction"), safe(user, "wardJurisdiction"))) continue;
            }
            if ("WORKER".equals(role)) continue;
            out.add(appealRow(row));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> reviewAppeal(String id, Map<String, Object> body, Map<String, Object> user) {
        if (!isAnyRole(user, "ADMIN", "DEPARTMENT_HEAD")) return error("Only Admin or Department Head can review appeals.");
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM sp_appeals WHERE id=?", id);
        if (rows.isEmpty()) return error("Appeal not found.");
        String status = safe(body, "status", "REOPENED").toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVED", "REJECTED", "REOPENED").contains(status)) return error("Invalid appeal status.");
        String grievanceId = String.valueOf(rows.get(0).get("grievance_id"));
        Map<String, Object> grievance = getGrievance(grievanceId);
        if (isRole(user, "DEPARTMENT_HEAD") && (grievance == null || !sameScope(safe(grievance, "assignedDepartment"), safe(user, "department")) || !sameScope(safe(grievance, "wardJurisdiction"), safe(user, "wardJurisdiction")))) return error("Appeal belongs to another department or ward.");
        db.update("UPDATE sp_appeals SET status=?,reviewer_id=?,reviewer_note=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", status, user.get("id"), safe(body, "reviewerNote"), id);
        // APPROVED is the human decision to reopen the service request. REOPENED remains
        // accepted as a backwards-compatible explicit decision value. REJECTED leaves the
        // complaint state unchanged.
        boolean reopen = "APPROVED".equals(status) || "REOPENED".equals(status);
        String nextComplaintStatus = reopen ? "REOPENED" : safe(grievance, "status", "RESOLVED");
        db.update("UPDATE sp_grievances SET appeal_status=?,status=?,resolved_at=CASE WHEN ? THEN NULL ELSE resolved_at END,updated_at=CURRENT_TIMESTAMP WHERE id=?", status, nextComplaintStatus, reopen, grievanceId);
        if (reopen && (grievance == null || !notBlank(safe(grievance, "assignedWorkerId")))) {
            autoAssignFirstWorker(grievanceId, safe(grievance, "assignedDepartment"), safe(grievance, "wardJurisdiction"), user);
        }
        db.update("INSERT INTO sp_complaint_updates(id,grievance_id,actor_id,status,note,evidence,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), grievanceId, user.get("id"), nextComplaintStatus,
                "Appeal " + status.toLowerCase(Locale.ROOT) + " by human reviewer" + (reopen ? "; complaint reopened for corrective action" : "; complaint remains in its existing state"), null);
        audit(user, grievanceId, "APPEAL_REVIEWED", Map.of("appealId", id, "status", status, "complaintStatus", nextComplaintStatus));
        notifyUser(safe(grievance, "userId"), "Appeal reviewed", safe(grievance, "trackingCode") + " appeal was " + status.toLowerCase(Locale.ROOT) + ".", "APPEAL");
        notifyRole("AUDITOR", "Appeal reviewed", safe(grievance, "trackingCode") + " appeal was " + status.toLowerCase(Locale.ROOT) + ".", "AUDIT");
        if (reopen && notBlank(safe(grievance, "assignedWorkerId"))) notifyUser(safe(grievance, "assignedWorkerId"), "Task reopened", safe(grievance, "trackingCode") + " was reopened after appeal review.", "TASK");
        if (reopen) notifyDepartmentHeads(safe(grievance, "assignedDepartment"), safe(grievance, "wardJurisdiction"), "Appeal approved - action required", safe(grievance, "trackingCode") + " was reopened after an approved appeal.", "APPEAL");
        return Map.of("success", true, "data", appeal(id), "complaintStatus", nextComplaintStatus);
    }

    private Map<String, Object> appeal(String id) { return appealRow(db.queryForMap("SELECT * FROM sp_appeals WHERE id=?", id)); }

    private Map<String, Object> appealRow(Map<String, Object> row) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", row.get("id")); a.put("grievanceId", row.get("grievance_id")); a.put("citizenId", row.get("citizen_id"));
        a.put("reason", row.get("reason")); a.put("evidence", safe(row, "evidence")); a.put("status", row.get("status"));
        a.put("reviewerNote", safe(row, "reviewer_note")); a.put("createdAt", row.get("created_at")); a.put("updatedAt", row.get("updated_at"));
        return a;
    }

    public Map<String, Object> rowToGrievance(Map<String, Object> row) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("id", row.get("id")); g.put("trackingCode", row.get("tracking_code")); g.put("citizenName", row.get("citizen_name"));
        g.put("contactEmail", row.get("contact_email")); g.put("userId", row.get("user_id")); g.put("originalText", row.get("description"));
        g.put("detectedLanguage", row.get("detected_language")); g.put("languageName", LANGUAGE_NAMES.getOrDefault(safe(row, "detected_language", "en"), "English"));
        g.put("languageConfidence", row.get("language_confidence")); g.put("englishTranslation", row.get("english_translation"));
        g.put("category", row.get("category")); g.put("categoryConfidence", row.get("category_confidence"));
        g.put("duplicateOfId", row.get("duplicate_of_id")); g.put("similarityScore", row.get("similarity_score"));
        g.put("assignedDepartment", row.get("assigned_department")); g.put("assignedWorkerId", row.get("assigned_worker_id")); g.put("assignedWorkerName", row.get("assigned_worker_name"));
        g.put("urgency", row.get("urgency")); g.put("priority", row.get("priority")); g.put("status", row.get("status"));
        g.put("wardJurisdiction", row.get("ward")); g.put("pincode", row.get("pincode")); g.put("streetAddress", row.get("street_address"));
        g.put("locality", row.get("locality")); g.put("landmark", row.get("landmark")); g.put("beforeEvidence", row.get("before_evidence"));
        g.put("afterEvidence", row.get("after_evidence")); g.put("resolutionNotes", row.get("resolution_notes")); g.put("appealStatus", row.get("appeal_status"));
        g.put("appealReason", row.get("appeal_reason")); g.put("createdAt", row.get("created_at")); g.put("updatedAt", row.get("updated_at")); g.put("resolvedAt", row.get("resolved_at"));
        g.put("mapQuery", String.join(", ", Arrays.asList(safe(row,"street_address"), safe(row,"locality"), safe(row,"landmark"), safe(row,"pincode"), "Mumbai")).replaceAll("^, |, , |, $", "").trim());
        g.put("isEmergency", row.get("is_emergency"));
        Object rawData = row.get("data");
        Map<String,Object> dataMap=null;
        if (rawData instanceof Map<?,?> dm) dataMap=toStringMap(dm);
        else if (rawData != null) { try { dataMap=mapper.readValue(String.valueOf(rawData), new TypeReference<Map<String,Object>>() {}); } catch(Exception ignored) {} }
        if (dataMap != null) {
            Object analysisObj = dataMap.get("analysis");
            if (analysisObj instanceof Map<?,?> am) {
                Map<String,Object> analysisMap=toStringMap(am);
                g.put("aiProvider", analysisMap.get("aiProvider"));
                g.put("aiModel", analysisMap.get("aiModel"));
                g.put("aiPriority", analysisMap.get("aiPriority"));
                g.put("aiPriorityConfidence", analysisMap.get("aiPriorityConfidence"));
                g.put("aiHumanReviewRequired", analysisMap.get("aiHumanReviewRequired"));
            }
        }
        g.put("latitude", row.get("latitude")); g.put("longitude", row.get("longitude")); g.put("locationAccuracy", row.get("location_accuracy"));
        g.put("sla", slaInfo(row));
        try {
            Object data = row.get("data");
            Map<String,Object> parsed;
            if (data instanceof String) parsed = mapper.readValue((String) data, new TypeReference<Map<String, Object>>() {});
            else if (data instanceof Map<?,?> m) { parsed = new LinkedHashMap<>(); m.forEach((k,v)->parsed.put(String.valueOf(k),v)); }
            else parsed = new LinkedHashMap<>();
            g.put("data", parsed);
            Object loc = parsed.get("location");
            if (loc instanceof Map<?,?> lm) {
                if (g.get("latitude") == null) g.put("latitude", lm.get("latitude"));
                if (g.get("longitude") == null) g.put("longitude", lm.get("longitude"));
                if (g.get("locationAccuracy") == null) g.put("locationAccuracy", lm.get("accuracyMeters"));
            }
            Object at = parsed.get("attachments");
            List<?> attachmentList = at instanceof List<?> ? (List<?>) at : List.of();
            g.put("attachments", attachmentList);
        } catch (Exception ignored) { g.put("data", Map.of()); g.put("attachments", List.of()); }
        return g;
    }

    
    private Map<String, Object> slaInfo(Map<String, Object> row) {
        int allowed = row.get("sla_hours") instanceof Number ? ((Number) row.get("sla_hours")).intValue() : 72;
        LocalDateTime created = toLocalDateTime(row.get("created_at"));
        LocalDateTime now=LocalDateTime.now();
        long remaining = SlaPolicy.remainingMinutes(created,allowed,now);
        long allowedMinutes = allowed * 60L;
        double percent = allowedMinutes == 0 ? 0 : Math.max(0, Math.min(100, remaining * 100.0 / allowedMinutes));
        LocalDateTime resolved=toLocalDateTime(row.get("resolved_at"));
        boolean breached=SlaPolicy.breached(created,resolved,safe(row,"status"),allowed,now);
        double breachProbability = row.get("predicted_breach_probability") instanceof Number ? ((Number) row.get("predicted_breach_probability")).doubleValue() : 0.12;
        String severity = breached ? "BREACHED" : (remaining <= 60 ? "CRITICAL" : (remaining <= 6 * 60 ? "HIGH" : (percent <= 35 ? "MEDIUM" : "LOW")));
        String deadlineAt = created == null ? "" : created.plusHours(allowed).toString();
        return Map.of(
            "allowedHours", allowed,
            "remainingMinutes", remaining,
            "remainingSeconds", remaining * 60L,
            "remainingHours", Math.round(remaining / 60.0 * 10.0) / 10.0,
            "percentRemaining", Math.round(percent),
            "isBreached", breached,
            "severity", severity,
            "deadlineAt", deadlineAt,
            "predictedBreachProbability", breachProbability
        );
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (value instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        if (value instanceof LocalDateTime l) return l;
        try { return value == null ? null : LocalDateTime.parse(String.valueOf(value).replace(' ', 'T')); } catch (Exception e) { return null; }
    }

    public Map<String, Object> requestPasswordOtp(Map<String, Object> body) {
        ensureSeeds();
        String identifier = safe(body, "identifier").trim().toLowerCase(Locale.ROOT);
        if (identifier.isBlank()) return error("Enter your username or email.");
        List<Map<String,Object>> rows = db.queryForList("SELECT id,username,email,role,full_name FROM sp_users WHERE LOWER(username)=? OR LOWER(email)=? LIMIT 1", identifier, identifier);
        if (rows.isEmpty()) return error("No account was found for that username/email.");
        Map<String,Object> row = rows.get(0);
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        db.update("DELETE FROM sp_password_otps WHERE user_id=?", row.get("id"));
        db.update("INSERT INTO sp_password_otps(id,user_id,otp_hash,expires_at,used,attempts) VALUES(?,?,?,CURRENT_TIMESTAMP + INTERVAL '10 minutes',false,0)",
            "OTP-" + UUID.randomUUID(), row.get("id"), hash(otp));
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("message", "Verification code generated. It expires in 10 minutes.");
        out.put("destination", maskEmail(safe(row,"email")));
        out.put("expiresInSeconds", 600);
        // Local/offline-friendly mode: show the code in the in-app verification popup.
        // A production SMS/email provider can replace this without exposing password hashes.
        out.put("otpPreview", otp);
        return out;
    }

    public Map<String, Object> resetPasswordWithOtp(Map<String, Object> body) {
        String identifier = safe(body, "identifier").trim().toLowerCase(Locale.ROOT);
        String otp = safe(body, "otp").trim();
        String newPassword = safe(body, "newPassword");
        if (identifier.isBlank() || otp.isBlank() || newPassword.length() < 6) return error("Username/email, OTP and a 6+ character new password are required.");
        List<Map<String,Object>> users = db.queryForList("SELECT id,email FROM sp_users WHERE LOWER(username)=? OR LOWER(email)=? LIMIT 1", identifier, identifier);
        if (users.isEmpty()) return error("Invalid verification request.");
        String userId = safe(users.get(0), "id");
        List<Map<String,Object>> rows = db.queryForList("SELECT id,otp_hash FROM sp_password_otps WHERE user_id=? AND used=false AND expires_at>CURRENT_TIMESTAMP ORDER BY expires_at DESC LIMIT 1", userId);
        if (rows.isEmpty()) return error("OTP is invalid or expired. Request a new OTP.");
        Map<String,Object> row = rows.get(0);
        if (!hash(otp).equals(safe(row,"otp_hash"))) {
            db.update("UPDATE sp_password_otps SET attempts=attempts+1 WHERE id=?", row.get("id"));
            return error("Incorrect OTP.");
        }
        db.update("UPDATE sp_users SET password_value=? WHERE id=?", hash(newPassword), userId);
        db.update("UPDATE sp_password_otps SET used=true WHERE id=?", row.get("id"));
        audit(null, userId, "PASSWORD_RESET", Map.of("channel","in-app-otp"));
        return Map.of("success", true, "message", "Password reset successfully. You can sign in now.");
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) return "your registered contact";
        String[] p=email.split("@",2); String local=p[0];
        String masked=local.length()<=2?local.charAt(0)+"***":local.substring(0,1)+"***"+local.substring(local.length()-1);
        return masked+"@"+p[1];
    }

    public Map<String, Object> adminWardOverview(Map<String,Object> actor) {
        if (!isRole(actor,"ADMIN")) return Map.of("success",false,"error","Admin only.");
        ensureWardMaster();
        List<Map<String,Object>> wards=new ArrayList<>();
        for(Map<String,Object> master:db.queryForList("SELECT id,code,name,zone FROM sp_wards WHERE active=true ORDER BY id")){
            String wardName=safe(master,"name"), fullWard=wardName+" / "+safe(master,"zone");
            Map<String,Object> x=new LinkedHashMap<>(master);
            x.put("ward",fullWard); x.put("zone",safe(master,"zone"));
            x.put("total",count("SELECT COUNT(*) FROM sp_grievances WHERE ward=?",fullWard));
            x.put("open",count("SELECT COUNT(*) FROM sp_grievances WHERE ward=? AND status NOT IN ('RESOLVED','COMPLETED','CLOSED')",fullWard));
            x.put("resolved",count("SELECT COUNT(*) FROM sp_grievances WHERE ward=? AND status IN ('RESOLVED','COMPLETED','CLOSED')",fullWard));
            x.put("breached",count("SELECT COUNT(*) FROM sp_grievances WHERE ward=? AND ((resolved_at IS NOT NULL AND resolved_at > created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour')) OR (resolved_at IS NULL AND CURRENT_TIMESTAMP > created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour')))",fullWard));
            x.put("critical",count("SELECT COUNT(*) FROM sp_grievances WHERE ward=? AND UPPER(COALESCE(priority,''))='CRITICAL'",fullWard));
            x.put("high",count("SELECT COUNT(*) FROM sp_grievances WHERE ward=? AND UPPER(COALESCE(priority,''))='HIGH'",fullWard));
            x.put("workers",count("SELECT COUNT(*) FROM sp_users WHERE role='WORKER' AND blocked=false AND ward_jurisdiction=?",fullWard));
            x.put("departmentHeads",count("SELECT COUNT(*) FROM sp_users WHERE role='DEPARTMENT_HEAD' AND blocked=false AND ward_jurisdiction=?",fullWard));
            x.put("departments",db.queryForList("SELECT COALESCE(NULLIF(assigned_department,''),'Routing pending') AS label,COUNT(*) AS count FROM sp_grievances WHERE ward=? GROUP BY assigned_department ORDER BY count DESC LIMIT 4",fullWard));
            x.put("categories",db.queryForList("SELECT COALESCE(category,'OTHER') AS label,COUNT(*) AS count FROM sp_grievances WHERE ward=? GROUP BY category ORDER BY count DESC LIMIT 4",fullWard));
            int total=((Number)x.get("total")).intValue(),resolved=((Number)x.get("resolved")).intValue();
            x.put("resolutionRate",total==0?0:Math.round(resolved*10000.0/total)/100.0);
            wards.add(x);
        }
        return Map.of("success",true,"wards",wards,"wardCount",wards.size());
    }

    public Map<String,Object> adminWidgetPreferences(Map<String,Object> actor) {
        if(!isRole(actor,"ADMIN")) return Map.of("success",false,"error","Admin only.");
        List<Map<String,Object>> rows=db.queryForList("SELECT widget_order,pinned_widgets FROM sp_admin_preferences WHERE user_id=?",safe(actor,"id"));
        if(rows.isEmpty()) return Map.of("success",true,"order",List.of(0,1,2,3,4,5,6,7,8,9),"pinned",List.of(0,1,2,3,4,7,8,9));
        return Map.of("success",true,"order",jsonList(rows.get(0).get("widget_order")),"pinned",jsonList(rows.get(0).get("pinned_widgets")));
    }

    public Map<String,Object> saveAdminWidgetPreferences(Map<String,Object> body,Map<String,Object> actor) {
        if(!isRole(actor,"ADMIN")) return Map.of("success",false,"error","Admin only.");
        List<Integer> order=intList(body.get("order")), pinned=intList(body.get("pinned"));
        if(order.size()!=10) return error("Widget order must contain all 10 widgets.");
        String orderJson=jsonSafe(order), pinnedJson=jsonSafe(pinned);
        db.update("INSERT INTO sp_admin_preferences(user_id,widget_order,pinned_widgets,updated_at) VALUES(?,?::jsonb,?::jsonb,CURRENT_TIMESTAMP) ON CONFLICT(user_id) DO UPDATE SET widget_order=EXCLUDED.widget_order,pinned_widgets=EXCLUDED.pinned_widgets,updated_at=CURRENT_TIMESTAMP",safe(actor,"id"),orderJson,pinnedJson);
        audit(actor,safe(actor,"id"),"ADMIN_WIDGETS_UPDATED",Map.of("order",order,"pinned",pinned));
        return Map.of("success",true,"order",order,"pinned",pinned);
    }

    public Map<String,Object> resetAdminWidgetPreferences(Map<String,Object> actor) {
        if(!isRole(actor,"ADMIN")) return Map.of("success",false,"error","Admin only.");
        db.update("DELETE FROM sp_admin_preferences WHERE user_id=?",safe(actor,"id"));
        audit(actor,safe(actor,"id"),"ADMIN_WIDGETS_RESET",Map.of());
        return adminWidgetPreferences(actor);
    }

    public Map<String, Object> analytics(Map<String, Object> user) {
        if (user == null) return Map.of("success", false, "error", "Unauthorized");
        String role = safe(user, "role");
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if ("CITIZEN".equals(role)) { where.append(" AND user_id=?"); params.add(user.get("id")); }
        else if ("WORKER".equals(role)) { where.append(" AND assigned_worker_id=?"); params.add(user.get("id")); }
        else if ("DEPARTMENT_HEAD".equals(role)) { where.append(" AND assigned_department=? AND ward=?"); params.add(user.get("department")); params.add(user.get("wardJurisdiction")); }
        else if ("AUDITOR".equals(role) || "ADMIN".equals(role)) { }
        else return Map.of("success", false, "error", "Role has no analytics access.");

        Object[] p = params.toArray();
        String base = " FROM sp_grievances" + where;
        int total = count("SELECT COUNT(*)" + base, p);
        int resolved = count("SELECT COUNT(*)" + base + " AND status IN ('RESOLVED','COMPLETED','CLOSED')", p);
        int open = total - resolved;
        int assigned = count("SELECT COUNT(*)" + base + " AND status='ASSIGNED'", p);
        int inProgress = count("SELECT COUNT(*)" + base + " AND status='IN_PROGRESS'", p);
        int breached = count("SELECT COUNT(*)" + base + " AND ((resolved_at IS NOT NULL AND resolved_at > created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour')) OR (resolved_at IS NULL AND CURRENT_TIMESTAMP > created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour')))", p);
        int critical = count("SELECT COUNT(*)" + base + " AND UPPER(COALESCE(priority,''))='CRITICAL'", p);
        int high = count("SELECT COUNT(*)" + base + " AND UPPER(COALESCE(priority,''))='HIGH'", p);
        int medium = count("SELECT COUNT(*)" + base + " AND UPPER(COALESCE(priority,''))='MEDIUM'", p);
        int low = count("SELECT COUNT(*)" + base + " AND UPPER(COALESCE(priority,''))='LOW'", p);

        List<Map<String, Object>> departments = db.queryForList("SELECT COALESCE(NULLIF(assigned_department,''),'Routing pending') AS label,COUNT(*) AS count FROM sp_grievances" + where + " GROUP BY assigned_department ORDER BY count DESC", p);
        List<Map<String, Object>> languages = db.queryForList("SELECT COALESCE(NULLIF(detected_language,''),'unknown') AS label,COUNT(*) AS count FROM sp_grievances" + where + " GROUP BY detected_language ORDER BY count DESC", p);
        List<Map<String, Object>> wards = db.queryForList("SELECT ward AS label,COUNT(*) AS count,SUM(CASE WHEN ((resolved_at IS NOT NULL AND resolved_at > created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour')) OR (resolved_at IS NULL AND CURRENT_TIMESTAMP > created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour'))) THEN 1 ELSE 0 END) AS breached FROM sp_grievances" + where + " GROUP BY ward ORDER BY count DESC", p);
        List<Map<String, Object>> statuses = db.queryForList("SELECT status AS label,COUNT(*) AS count FROM sp_grievances" + where + " GROUP BY status ORDER BY count DESC", p);
        List<Map<String, Object>> categories = db.queryForList("SELECT category AS label,COUNT(*) AS count FROM sp_grievances" + where + " GROUP BY category ORDER BY count DESC", p);
        List<Map<String, Object>> priority = db.queryForList("SELECT COALESCE(NULLIF(UPPER(priority),''),'MEDIUM') AS label,COUNT(*) AS count FROM sp_grievances" + where + " GROUP BY COALESCE(NULLIF(UPPER(priority),''),'MEDIUM') ORDER BY count DESC", p);

        List<Map<String,Object>> slaBuckets = new ArrayList<>();
        slaBuckets.add(Map.of("label","BREACHED","count",breached));
        slaBuckets.add(Map.of("label","< 1 hour","count",count("SELECT COUNT(*)" + base + " AND status NOT IN ('RESOLVED','COMPLETED','CLOSED') AND created_at >= CURRENT_TIMESTAMP - (COALESCE(sla_hours,72) * INTERVAL '1 hour') AND created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour') <= CURRENT_TIMESTAMP + INTERVAL '1 hour'", p)));
        slaBuckets.add(Map.of("label","1–6 hours","count",count("SELECT COUNT(*)" + base + " AND status NOT IN ('RESOLVED','COMPLETED','CLOSED') AND created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour') > CURRENT_TIMESTAMP + INTERVAL '1 hour' AND created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour') <= CURRENT_TIMESTAMP + INTERVAL '6 hours'", p)));
        slaBuckets.add(Map.of("label","> 6 hours","count",count("SELECT COUNT(*)" + base + " AND status NOT IN ('RESOLVED','COMPLETED','CLOSED') AND created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour') > CURRENT_TIMESTAMP + INTERVAL '6 hours'", p)));

        List<Map<String,Object>> trend = db.queryForList(
            "SELECT TO_CHAR(day,'DD Mon') AS label,COUNT(g.id) AS count " +
            "FROM generate_series(CURRENT_DATE - INTERVAL '6 days',CURRENT_DATE,INTERVAL '1 day') day " +
            "LEFT JOIN sp_grievances g ON DATE(g.created_at)=DATE(day)" +
            (where.toString().equals(" WHERE 1=1") ? "" : where.substring(" WHERE 1=1".length())) +
            " GROUP BY day ORDER BY day", p
        );

        List<Map<String,Object>> wardGeo = db.queryForList(
            "SELECT ward AS label,COUNT(*) AS count," +
            "ROUND(AVG(latitude)::numeric,6) AS latitude,ROUND(AVG(longitude)::numeric,6) AS longitude," +
            "SUM(CASE WHEN ((resolved_at IS NOT NULL AND resolved_at > created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour')) OR (resolved_at IS NULL AND CURRENT_TIMESTAMP > created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour'))) THEN 1 ELSE 0 END) AS breached " +
            "FROM sp_grievances" + where + " GROUP BY ward ORDER BY count DESC", p
        );

        Map<String,Object> summary = new LinkedHashMap<>();
        summary.put("total", total); summary.put("resolved", resolved); summary.put("open", open);
        summary.put("assigned", assigned); summary.put("inProgress", inProgress); summary.put("breached", breached);
        summary.put("critical", critical); summary.put("high", high); summary.put("medium", medium); summary.put("low", low);
        summary.put("resolutionRate", total == 0 ? 0 : Math.round(resolved * 10000.0 / total) / 100.0);
        summary.put("breachRate", total == 0 ? 0 : Math.round(breached * 10000.0 / total) / 100.0);
        Double routingConfidence = db.queryForObject("SELECT AVG(category_confidence) FROM sp_grievances" + where, p, Double.class);
        summary.put("routingConfidence", routingConfidence == null ? "—" : Math.round(routingConfidence * 10000.0) / 100.0 + "%");
        summary.put("resolvedLast12h", count("SELECT COUNT(*) FROM sp_grievances" + where + " AND resolved_at >= CURRENT_TIMESTAMP - INTERVAL '12 hours'", p));
        summary.put("newLast12h", count("SELECT COUNT(*) FROM sp_grievances" + where + " AND created_at >= CURRENT_TIMESTAMP - INTERVAL '12 hours'", p));

        return Map.of("success", true, "data", Map.of(
            "summary", summary, "departments", departments, "languages", languages,
            "wards", wards, "statuses", statuses, "categories", categories,
            "priority", priority, "slaBuckets", slaBuckets, "trend", trend, "wardGeo", wardGeo
        ));
    }

    private List<Integer> intList(Object value) {
        List<Integer> out=new ArrayList<>(); if(value instanceof List<?> list) for(Object v:list) try{out.add(Integer.parseInt(String.valueOf(v)));}catch(Exception ignored){} return out;
    }
    private List<Integer> jsonList(Object value) {
        if(value instanceof List<?> l) return intList(l);
        if(value==null) return List.of();
        try { return intList(mapper.readValue(String.valueOf(value),new TypeReference<List<Object>>(){})); } catch(Exception e) { return List.of(); }
    }

    private int count(String sql, Object... params) { Integer n = db.queryForObject(sql, params, Integer.class); return n == null ? 0 : n; }

    public List<Map<String, Object>> managedUsers() {
        ensureSeeds();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : db.queryForList("SELECT * FROM sp_users ORDER BY role,full_name")) out.add(userRow(row));
        return out;
    }

    public List<Map<String, Object>> managedUsersFor(Map<String, Object> actor) {
        if (actor == null) return List.of();
        String role = safe(actor, "role");
        if ("ADMIN".equals(role)) return managedUsers();
        if ("DEPARTMENT_HEAD".equals(role)) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : db.queryForList("SELECT * FROM sp_users WHERE role='WORKER' AND department=? AND ward_jurisdiction=? AND blocked=false ORDER BY full_name", actor.get("department"), actor.get("wardJurisdiction"))) out.add(userRow(row));
            return out;
        }
        return List.of();
    }

    public List<Map<String, Object>> departments() { return db.queryForList("SELECT id,code,name,sla_hours FROM sp_departments ORDER BY name"); }

    public Map<String, Object> departmentCreate(Map<String, Object> body, Map<String, Object> actor) {
        if (!isRole(actor, "ADMIN")) return error("Admin only.");
        String code = safe(body, "code", "CUSTOM").toUpperCase(Locale.ROOT);
        db.update("INSERT INTO sp_departments(id,code,name,sla_hours) VALUES(?,?,?,?)", "D-" + UUID.randomUUID(), code, safe(body, "name", "New Department"), integer(body, "slaHours", 48));
        audit(actor, null, "DEPARTMENT_CREATED", body);
        return Map.of("success", true);
    }

    public Map<String, Object> departmentUpdate(String id, Map<String, Object> body, Map<String, Object> actor) {
        if (!isRole(actor, "ADMIN")) return error("Admin only.");
        db.update("UPDATE sp_departments SET name=COALESCE(?,name),sla_hours=COALESCE(?,sla_hours) WHERE id=?", nullable(body, "name"), body.get("slaHours"), id);
        audit(actor, id, "DEPARTMENT_UPDATED", body);
        return Map.of("success", true);
    }

    public Map<String, Object> departmentDelete(String id, Map<String, Object> actor) {
        if (!isRole(actor, "ADMIN")) return error("Admin only.");
        Integer n = db.queryForObject("SELECT COUNT(*) FROM sp_grievances g JOIN sp_departments d ON d.name=g.assigned_department WHERE d.id=?", Integer.class, id);
        if (n != null && n > 0) return error("Department has linked complaints; keep it active for historical records.");
        db.update("DELETE FROM sp_departments WHERE id=?", id);
        audit(actor, id, "DEPARTMENT_DELETED", Map.of());
        return Map.of("success", true);
    }

    @Transactional
    public Map<String, Object> managedCreate(Map<String, Object> body, Map<String, Object> actor) {
        if (!isRole(actor, "ADMIN")) return error("Admin only.");
        String role = safe(body, "role", "CITIZEN").toUpperCase(Locale.ROOT);
        if (!Set.of("CITIZEN", "ADMIN", "DEPARTMENT_HEAD", "WORKER", "AUDITOR").contains(role)) return error("Invalid role.");
        String email = safe(body, "email").trim().toLowerCase(Locale.ROOT);
        if (email.isBlank()) return error("Email is required.");
        String username = safe(body, "username", email).trim().toLowerCase(Locale.ROOT);
        if (username.isBlank()) return error("Username is required.");
        String department = safe(body, "department").trim();
        String ward = safe(body, "ward", "ALL_WARDS").trim();
        String preferredLanguage = safe(body, "preferredLanguage", "en");
        if (!Set.of("en","hi","mr").contains(preferredLanguage)) return error("Only English, Hindi and Marathi are supported.");
        if ("DEPARTMENT_HEAD".equals(role)) {
            if (department.isBlank()) return error("Department is required for a Department Head.");
            if (ward.isBlank() || "ALL_WARDS".equalsIgnoreCase(ward)) return error("Department Head must be assigned to one specific ward, not ALL_WARDS.");
            Integer headExists = db.queryForObject("SELECT COUNT(*) FROM sp_users WHERE role='DEPARTMENT_HEAD' AND department=? AND ward_jurisdiction=? AND blocked=false", Integer.class, department, ward);
            if (headExists != null && headExists > 0) return error("A Department Head already exists for this department and ward.");
        }
        Integer usernameExists = db.queryForObject("SELECT COUNT(*) FROM sp_users WHERE LOWER(username)=LOWER(?)", Integer.class, username);
        if (usernameExists != null && usernameExists > 0) return error("Username already exists: " + username);
        Integer emailExists = db.queryForObject("SELECT COUNT(*) FROM sp_users WHERE LOWER(email)=LOWER(?)", Integer.class, email);
        if (emailExists != null && emailExists > 0) return error("Email already exists: " + email);
        String id = "USR-" + UUID.randomUUID();
        try {
            db.update("INSERT INTO sp_users(id,username,email,password_value,full_name,phone,role,department,ward_jurisdiction,preferred_language,blocked) VALUES(?,?,?,?,?,?,?,?,?,?,false)",
                id, username, email, hash(safe(body, "password", "Welcome@123")), safe(body, "fullName", "New User"), safe(body, "phone"), role,
                department, ward, preferredLanguage);
            audit(actor, id, "USER_CREATED", Map.of("role", role, "department", safe(body, "department"), "ward", safe(body, "ward", "ALL_WARDS")));
            return Map.of("success", true, "data", userRow(db.queryForMap("SELECT * FROM sp_users WHERE id=?", id)));
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
            return error("Unable to create user: " + msg);
        }
    }

    public Map<String,Object> adminResetPassword(String id, Map<String,Object> body, Map<String,Object> actor) {
        if (!isRole(actor,"ADMIN")) return error("Admin only.");
        String role=safe(body,"role").toUpperCase(Locale.ROOT);
        if ("CITIZEN".equals(role)) return error("Citizen passwords are not visible or admin-resettable from this panel.");
        String newPassword=safe(body,"newPassword");
        if (newPassword.length()<6) return error("New password must contain at least 6 characters.");
        Integer exists=count("SELECT COUNT(*) FROM sp_users WHERE id=? AND role<>?",id,"CITIZEN");
        if (exists==0) return error("User not found or password reset is restricted.");
        db.update("UPDATE sp_users SET password_value=? WHERE id=?",hash(newPassword),id);
        audit(actor,id,"ADMIN_PASSWORD_RESET",Map.of("role",role));
        return Map.of("success",true,"message","Password reset successfully. Plaintext passwords are never stored or exposed.");
    }

    public Map<String, Object> managedUpdate(String id, Map<String, Object> body, Map<String, Object> actor) {
        if (!isRole(actor, "ADMIN")) return error("Admin only.");
        String role = safe(body, "role").toUpperCase(Locale.ROOT);
        String department = safe(body, "department").trim();
        String ward = safe(body, "ward", "ALL_WARDS").trim();
        String preferredLanguage = safe(body, "preferredLanguage", "en");
        if (!Set.of("en","hi","mr").contains(preferredLanguage)) return error("Only English, Hindi and Marathi are supported.");
        if ("DEPARTMENT_HEAD".equals(role)) {
            if (department.isBlank()) return error("Department is required for a Department Head.");
            if (ward.isBlank() || "ALL_WARDS".equalsIgnoreCase(ward)) return error("Department Head must be assigned to one specific ward, not ALL_WARDS.");
            Integer headExists = db.queryForObject("SELECT COUNT(*) FROM sp_users WHERE role='DEPARTMENT_HEAD' AND department=? AND ward_jurisdiction=? AND blocked=false AND id<>?", Integer.class, department, ward, id);
            if (headExists != null && headExists > 0) return error("A Department Head already exists for this department and ward.");
        }
        db.update("UPDATE sp_users SET full_name=COALESCE(?,full_name),phone=COALESCE(?,phone),role=COALESCE(?,role),department=COALESCE(?,department),ward_jurisdiction=COALESCE(?,ward_jurisdiction),preferred_language=COALESCE(?,preferred_language),blocked=COALESCE(?,blocked) WHERE id=?",
            nullable(body, "fullName"), nullable(body, "phone"), nullable(body, "role"), nullable(body, "department"), nullable(body, "ward"), preferredLanguage, body.get("blocked"), id);
        audit(actor, id, "USER_UPDATED", body);
        return Map.of("success", true, "data", userRow(db.queryForMap("SELECT * FROM sp_users WHERE id=?", id)));
    }

    public Map<String, Object> managedDelete(String id, Map<String, Object> actor) {
        if (!isRole(actor, "ADMIN")) return error("Admin only.");
        db.update("UPDATE sp_users SET blocked=true WHERE id=?", id);
        audit(actor, id, "USER_DEACTIVATED", Map.of());
        return Map.of("success", true);
    }

    private Map<String, Object> userRow(Map<String, Object> row) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", row.get("id")); u.put("username", row.get("username")); u.put("email", row.get("email"));
        u.put("name", row.get("full_name")); u.put("fullName", row.get("full_name")); u.put("phone", row.get("phone"));
        u.put("role", row.get("role")); u.put("department", row.get("department")); u.put("wardJurisdiction", row.get("ward_jurisdiction"));
        u.put("preferredLanguage", row.get("preferred_language")); u.put("address", row.get("address")); u.put("pincode", row.get("pincode"));
        u.put("zone", row.get("zone")); u.put("blocked", row.get("blocked")); u.put("createdAt", row.get("created_at")); u.put("lastLoginAt", row.get("last_login_at"));
        if ("WORKER".equals(safe(row, "role"))) {
            String id = safe(row, "id");
            u.put("assignedCount", count("SELECT COUNT(*) FROM sp_grievances WHERE assigned_worker_id=?", id));
            u.put("completedCount", count("SELECT COUNT(*) FROM sp_grievances WHERE assigned_worker_id=? AND status IN ('RESOLVED','COMPLETED','CLOSED')", id));
            u.put("pendingCount", count("SELECT COUNT(*) FROM sp_grievances WHERE assigned_worker_id=? AND status NOT IN ('RESOLVED','COMPLETED','CLOSED')", id));
        }
        if ("CITIZEN".equals(safe(row, "role"))) {
            String id = safe(row, "id");
            u.put("complaintCount", count("SELECT COUNT(*) FROM sp_grievances WHERE user_id=?", id));
            u.put("resolvedCount", count("SELECT COUNT(*) FROM sp_grievances WHERE user_id=? AND status IN ('RESOLVED','COMPLETED','CLOSED')", id));
        }
        return u;
    }

    public List<Map<String, Object>> auditLogs() { return db.queryForList("SELECT id,actor_id,action,entity_type,entity_id,details,created_at,ip_address FROM sp_audit_logs ORDER BY created_at DESC LIMIT 500"); }

    public String exportCsv(Map<String, Object> actor) { return exportCsv(actor, null); }

    public String exportCsv(Map<String, Object> actor, String requestedDepartment) {
        if (!isAnyRole(actor, "ADMIN", "AUDITOR", "DEPARTMENT_HEAD")) return "";
        String department = requestedDepartment;
        String wardScope = null;
        if (isRole(actor, "DEPARTMENT_HEAD")) { department = safe(actor, "department"); wardScope = safe(actor, "wardJurisdiction"); }
        StringBuilder sql = new StringBuilder("SELECT tracking_code,citizen_name,detected_language,category,assigned_department,assigned_worker_name,ward,priority,status,created_at,resolved_at FROM sp_grievances WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (notBlank(department)) { sql.append(" AND assigned_department=?"); params.add(department); }
        if (notBlank(wardScope)) { sql.append(" AND ward=?"); params.add(wardScope); }
        sql.append(" ORDER BY created_at DESC");
        List<Map<String,Object>> rows = db.queryForList(sql.toString(), params.toArray());
        StringBuilder csv = new StringBuilder("tracking_code,citizen,language,category,department,worker,ward,priority,status,created_at,resolved_at\n");
        for (Map<String, Object> row : rows) {
            String[] keys = {"tracking_code","citizen_name","detected_language","category","assigned_department","assigned_worker_name","ward","priority","status","created_at","resolved_at"};
            for (int i=0;i<keys.length;i++) { if(i>0) csv.append(','); String value=safe(row,keys[i]).replace("\"","\"\""); csv.append('\"').append(value).append('\"'); }
            csv.append('\n');
        }
        return csv.toString();
    }

    public byte[] exportPdf(Map<String,Object> actor, String requestedDepartment) {
        if (!isAnyRole(actor,"ADMIN","AUDITOR","DEPARTMENT_HEAD")) return new byte[0];
        String department=requestedDepartment, wardScope=null;
        if(isRole(actor,"DEPARTMENT_HEAD")){department=safe(actor,"department");wardScope=safe(actor,"wardJurisdiction");}
        StringBuilder sql=new StringBuilder("SELECT tracking_code,citizen_name,detected_language,category,assigned_department,assigned_worker_name,ward,pincode,priority,status,created_at FROM sp_grievances WHERE 1=1");
        List<Object> params=new ArrayList<>();
        if(notBlank(department)){sql.append(" AND assigned_department=?");params.add(department);}
        if(notBlank(wardScope)){sql.append(" AND ward=?");params.add(wardScope);}
        sql.append(" ORDER BY created_at DESC");
        List<Map<String,Object>> rows=db.queryForList(sql.toString(),params.toArray());
        List<String> lines=new ArrayList<>();
        lines.add("SamadhanPoint - Complaint Report");
        lines.add("Scope: "+(department==null||department.isBlank()?"All Departments":department)+(wardScope==null?"":" | "+wardScope));
        lines.add("Generated: "+LocalDateTime.now());
        lines.add("");
        lines.add("Tracking | Category | Department | Ward | Priority | Status");
        for(Map<String,Object> r:rows){
            String line=safe(r,"tracking_code")+" | "+safe(r,"category")+" | "+safe(r,"assigned_department")+" | "+safe(r,"ward")+" | "+safe(r,"priority")+" | "+safe(r,"status");
            line=line.replaceAll("[^\\x20-\\x7E]","?"); if(line.length()>120)line=line.substring(0,120); lines.add(line);
        }
        try{return buildSimplePdf(lines);}catch(Exception e){return new byte[0];}
    }

    private byte[] buildSimplePdf(List<String> lines) throws Exception {
        StringBuilder content=new StringBuilder("BT\n/F1 9 Tf\n45 760 Td\n");
        int n=0;
        for(String line:lines){
            if(n>0) content.append("0 -14 Td\n");
            content.append("(").append(pdfEscape(line)).append(") Tj\n"); n++;
            if(n>=50) break;
        }
        content.append("ET\n");
        byte[] stream=content.toString().getBytes(StandardCharsets.ISO_8859_1);
        List<String> objs=new ArrayList<>();
        objs.add("<< /Type /Catalog /Pages 2 0 R >>");
        objs.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        objs.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>");
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        objs.add("<< /Length "+stream.length+" >>\nstream\n"+new String(stream,StandardCharsets.ISO_8859_1)+"endstream");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write("%PDF-1.4\n%".getBytes(StandardCharsets.ISO_8859_1));
        List<Integer> offsets=new ArrayList<>(); offsets.add(0);
        for(int i=0;i<objs.size();i++){offsets.add(out.size());out.write((i+1+" 0 obj\n").getBytes(StandardCharsets.ISO_8859_1));out.write(objs.get(i).getBytes(StandardCharsets.ISO_8859_1));out.write("\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));}
        int xref=out.size();out.write(("xref\n0 "+(objs.size()+1)+"\n").getBytes(StandardCharsets.ISO_8859_1));out.write("0000000000 65535 f \n".getBytes(StandardCharsets.ISO_8859_1));
        for(int i=1;i<offsets.size();i++)out.write(String.format(Locale.ROOT,"%010d 00000 n \n",offsets.get(i)).getBytes(StandardCharsets.ISO_8859_1));
        out.write(("trailer\n<< /Size "+(objs.size()+1)+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private String pdfEscape(String s){return safe(s).replace("\\","\\\\").replace("(","\\(").replace(")","\\)").replaceAll("[^\\x20-\\x7E]","?");}

    /**
     * Reproducible evaluation evidence for the BIT-16 acceptance dossier. The ground-truth
     * corpus is synthetic/de-identified and lives under resources/evaluation. The evaluator
     * uses the same server-side deterministic triage guard used for routing, so the reported
     * baseline metrics are reproducible without an API key.
     */
    public Map<String,Object> evaluationSummary(Map<String,Object> actor) {
        if (!isAnyRole(actor, "ADMIN", "AUDITOR")) return Map.of("success", false, "error", "Evaluation access denied.");
        try {
            List<String> lines = new ArrayList<>();
            try (var in = getClass().getClassLoader().getResourceAsStream("evaluation/ground_truth.csv")) {
                if (in == null) return Map.of("success", false, "error", "Evaluation dataset not found.");
                try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line; while ((line = br.readLine()) != null) if (!line.isBlank() && !line.startsWith("id,")) lines.add(line);
                }
            }
            Map<String,Map<String,Integer>> cm=new LinkedHashMap<>();
            int correct=0,total=0;
            for(String line:lines){
                String[] parts=line.split(",",4); if(parts.length<4) continue;
                String expected=parts[2].trim(); String text=parts[3].trim();
                String predicted=safe(triageDataWithoutAi(text,defaultWard()),"suggestedCategory","OTHER");
                cm.computeIfAbsent(expected,k->new LinkedHashMap<>()).merge(predicted,1,Integer::sum);
                if(expected.equals(predicted)) correct++; total++;
            }
            Set<String> labels=new LinkedHashSet<>(DEPARTMENT.keySet());
            double f1Sum=0; int f1Labels=0;
            for(String label:labels){
                int tp=0,fp=0,fn=0;
                for(Map.Entry<String,Map<String,Integer>> e:cm.entrySet()){
                    if(label.equals(e.getKey())) tp += e.getValue().getOrDefault(label,0);
                    else fp += e.getValue().getOrDefault(label,0);
                    if(label.equals(e.getKey())) { for(Map.Entry<String,Integer> x:e.getValue().entrySet()) if(!label.equals(x.getKey())) fn += x.getValue(); }
                }
                double precision=(tp+fp)==0?0:(double)tp/(tp+fp);
                double recall=(tp+fn)==0?0:(double)tp/(tp+fn);
                if(precision+recall>0){f1Sum += 2*precision*recall/(precision+recall); f1Labels++;}
            }
            int liveTotal=count("SELECT COUNT(*) FROM sp_grievances");
            int liveResolved=count("SELECT COUNT(*) FROM sp_grievances WHERE status IN ('RESOLVED','COMPLETED','CLOSED')");
            int liveClosed=count("SELECT COUNT(*) FROM sp_grievances WHERE status='CLOSED'");
            int liveBreached=count("SELECT COUNT(*) FROM sp_grievances WHERE ((resolved_at IS NOT NULL AND resolved_at > created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour')) OR (resolved_at IS NULL AND CURRENT_TIMESTAMP > created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour')))");
            double slaCompliance=liveTotal==0?0:Math.round((liveTotal-liveBreached)*10000.0/liveTotal)/100.0;
            Map<String,Object> evaluationData = new LinkedHashMap<>();
            evaluationData.put("dataset", "BIT-16 synthetic multilingual ground truth");
            evaluationData.put("samples", total);
            evaluationData.put("classificationAccuracy", total==0?0:Math.round(correct*10000.0/total)/100.0);
            evaluationData.put("macroF1", f1Labels==0?0:Math.round((f1Sum/f1Labels)*10000.0)/10000.0);
            evaluationData.put("routingAccuracy", total==0?0:Math.round(correct*10000.0/total)/100.0);
            evaluationData.put("liveComplaintCount", liveTotal);
            evaluationData.put("liveResolvedCount", liveResolved);
            evaluationData.put("liveClosedCount", liveClosed);
            evaluationData.put("liveSlaCompliancePercent", slaCompliance);
            evaluationData.put("liveSlaBreachedCount", liveBreached);
            evaluationData.put("openAiConfigured", !groqKey.isBlank());
            evaluationData.put("groqModel", groqModel);
            evaluationData.put("aiArchitecture", "Groq Responses API + Java rule guard; Java remains authoritative for routing, SLA, RBAC and ward scope");
            return Map.of("success", true, "data", evaluationData);
        } catch(Exception e){ return Map.of("success",false,"error","Evaluation failed: "+e.getMessage()); }
    }

    public Map<String,Object> departmentReport(Map<String,Object> actor, String requestedDepartment) {
        if (!isAnyRole(actor,"ADMIN","AUDITOR","DEPARTMENT_HEAD")) return Map.of("success",false,"error","Report access denied.");
        String department = requestedDepartment;
        String wardScope = null;
        if (isRole(actor,"DEPARTMENT_HEAD")) { department=safe(actor,"department"); wardScope=safe(actor,"wardJurisdiction"); }
        String where=" WHERE 1=1"; List<Object> p=new ArrayList<>();
        if(notBlank(department)){where+=" AND assigned_department=?";p.add(department);}
        if(notBlank(wardScope)){where+=" AND ward=?";p.add(wardScope);}
        int total=count("SELECT COUNT(*) FROM sp_grievances"+where,p.toArray());
        int resolved=count("SELECT COUNT(*) FROM sp_grievances"+where+" AND status IN ('RESOLVED','COMPLETED','CLOSED')",p.toArray());
        int inProgress=count("SELECT COUNT(*) FROM sp_grievances"+where+" AND status='IN_PROGRESS'",p.toArray());
        int assigned=count("SELECT COUNT(*) FROM sp_grievances"+where+" AND status='ASSIGNED'",p.toArray());
        int open=count("SELECT COUNT(*) FROM sp_grievances"+where+" AND status NOT IN ('RESOLVED','COMPLETED','CLOSED')",p.toArray());
        int breached=count("SELECT COUNT(*) FROM sp_grievances"+where+" AND ((resolved_at IS NOT NULL AND resolved_at > created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour')) OR (resolved_at IS NULL AND CURRENT_TIMESTAMP > created_at + (COALESCE(sla_hours,72) * INTERVAL '1 hour')))",p.toArray());
        return Map.of("success",true,"data",Map.of("department",department==null?"ALL DEPARTMENTS":department,"total",total,"resolved",resolved,"inProgress",inProgress,"assigned",assigned,"open",open,"breached",breached,"resolutionRate",total==0?0:Math.round(resolved*10000.0/total)/100.0));
    }

    public Map<String, Object> assistant(String text, String language, Map<String, Object> user) {
        String question = safe(text).trim();
        String lang = safe(language, "en").toLowerCase(Locale.ROOT);
        if (question.isBlank()) return Map.of("success", false, "reply", "Please type your question.");

        // Use Groq for natural-language questions when configured. The assistant is informational;
        // authoritative complaint routing, permissions and workflow rules remain in Java.
        Map<String,Object> aiReply = openAiAssistantChat(question, lang, user);
        if (aiReply != null && !safe(aiReply, "reply").isBlank()) {
            aiReply.put("success", true);
            aiReply.put("modelUsed", groqModel);
            aiReply.put("aiProvider", "Groq");
            return aiReply;
        }

        String t = question.toLowerCase(Locale.ROOT);
        boolean status = t.contains("status") || t.contains("track") || t.contains("स्थिति") || t.contains("स्थिती") || t.contains("स्थिति बत") || t.contains("स्थिती सांगा");
        boolean sla = t.contains("sla") || t.contains("deadline") || t.contains("time limit") || t.contains("समय") || t.contains("मुदत") || t.contains("कितना समय");
        boolean appealQ = t.contains("appeal") || t.contains("अपील") || t.contains("अपील");
        boolean complaintQ = t.contains("complaint") || t.contains("grievance") || t.contains("शिकायत") || t.contains("तक्रार");
        boolean departmentQ = t.contains("department") || t.contains("विभाग");
        boolean workerQ = t.contains("worker") || t.contains("field staff") || t.contains("कर्मचारी") || t.contains("कामगार");
        boolean locationQ = t.contains("ward") || t.contains("pincode") || t.contains("location") || t.contains("gps") || t.contains("स्थान") || t.contains("वॉर्ड") || t.contains("पिनकोड");
        boolean duplicateQ = t.contains("duplicate") || t.contains("similar complaint") || t.contains("डुप्लिकेट") || t.contains("समान तक्रार");
        boolean notificationQ = t.contains("notification") || t.contains("सूचना") || t.contains("नोटिफिकेशन");
        boolean languageQ = t.contains("language") || t.contains("hindi") || t.contains("marathi") || t.contains("भाषा");
        boolean aiQ = t.contains("ai") || t.contains("artificial intelligence") || t.contains("ai triage");
        String reply;
        if ("hi".equals(lang)) {
            if (appealQ) reply="अपील केवल आपकी शिकायत RESOLVED/COMPLETED/CLOSED होने के बाद उपलब्ध होती है। Appeals खोलें या My Complaints में उसी शिकायत पर ‘Submit Appeal’ दबाएँ, कारण लिखें और चाहें तो evidence जोड़ें।";
            else if (status) reply="Track Status खोलें और अपना tracking code डालें। My Complaints में भी status, department, worker और SLA दिखाई देता है।";
            else if (sla) reply="SLA शिकायत की category और priority के आधार पर तय होता है। Complaint details में remaining time और breach risk देख सकते हैं।";
            else if (locationQ) reply="Complaint की location login location से अलग होती है। GPS उपलब्ध होने पर system complaint की actual location से pincode और municipal ward resolve करता है और उसी scope में routing करता है।";
            else if (duplicateQ) reply="System पहले से मौजूद complaints से text similarity check करता है। High similarity मिलने पर possible duplicate दिखाया जाता है और human review किया जा सकता है।";
            else if (departmentQ) reply="AI category suggest करता है, फिर Java routing rules category और complaint location के आधार पर सही department चुनते हैं। Final routing server-side होती है।";
            else if (workerQ) reply="Worker को उसी department और ward scope में complaint assign किया जाता है। Worker ASSIGNED से IN_PROGRESS और फिर RESOLVED कर सकता है; citizen verification के बाद complaint CLOSED होती है।";
            else if (notificationQ) reply="Notifications complaint submission, routing, assignment, status change, resolution, verification और appeal review जैसे workflow events के लिए दिखाई जाती हैं।";
            else if (languageQ) reply="SamadhanPoint English, Hindi और Marathi complaints support करता है। Detected language और confidence complaint details में save होते हैं।";
            else if (aiQ) reply="AI triage language detection, category suggestion, English translation, priority/urgency support और duplicate/human-review signals में मदद करता है। Security, routing और workflow rules Java backend में authoritative हैं।";
            else if (complaintQ) reply="New Complaint से शिकायत दर्ज करें। Description, location और evidence दें। System language, category, department, priority, SLA और possible duplicate calculate करता है।";
            else reply="मैं SamadhanPoint के complaint filing, tracking, GPS/ward, departments, workers, SLA, duplicate detection, notifications, appeals और AI triage के बारे में सवालों का जवाब दे सकता हूँ। Groq configured होने पर मैं सामान्य natural-language questions भी answer कर सकता हूँ।";
        } else if ("mr".equals(lang)) {
            if (appealQ) reply="तुमची तक्रार RESOLVED/COMPLETED/CLOSED झाल्यानंतरच अपील उपलब्ध होते. Appeals उघडा किंवा My Complaints मधील त्या तक्रारीवर ‘Submit Appeal’ निवडा, कारण लिहा आणि आवश्यक असल्यास evidence जोडा.";
            else if (status) reply="Track Status उघडा आणि tracking code टाका. My Complaints मध्ये status, department, worker आणि SLA देखील दिसतात.";
            else if (sla) reply="SLA तक्रारीच्या category आणि priority नुसार ठरतो. Complaint details मध्ये उरलेला वेळ आणि breach risk पाहता येतो.";
            else if (locationQ) reply="Complaint ची location login location पेक्षा स्वतंत्र असते. GPS उपलब्ध असल्यास system actual location वरून pincode आणि municipal ward शोधतो आणि त्यानुसार routing करतो.";
            else if (duplicateQ) reply="System आधीच्या तक्रारींशी text similarity तपासतो. जास्त similarity आढळल्यास possible duplicate दाखवला जातो आणि human review होऊ शकतो.";
            else if (departmentQ) reply="AI category सुचवतो आणि Java routing rules category व complaint location नुसार योग्य department निवडतात. Final routing server-side होते.";
            else if (workerQ) reply="Worker ला त्याच department आणि ward scope मधील तक्रार दिली जाते. Worker ASSIGNED → IN_PROGRESS → RESOLVED करतो; citizen verification नंतर complaint CLOSED होते.";
            else if (notificationQ) reply="Complaint submission, routing, assignment, status change, resolution, verification आणि appeal review यांसाठी notifications मिळतात.";
            else if (languageQ) reply="SamadhanPoint English, Hindi आणि Marathi तक्रारी support करतो. Detected language आणि confidence complaint details मध्ये जतन होतात.";
            else if (aiQ) reply="AI language detection, category suggestion, English translation, priority/urgency, duplicate detection आणि human-review signals मध्ये मदत करतो. Security आणि final routing Java backend मध्ये असते.";
            else if (complaintQ) reply="New Complaint मधून तक्रार नोंदवा. Description, location आणि evidence द्या. System language, category, department, priority, SLA आणि possible duplicate ठरवतो.";
            else reply="मी SamadhanPoint मधील complaint filing, tracking, GPS/ward, departments, workers, SLA, duplicate detection, notifications, appeals आणि AI triage याबद्दल मदत करू शकतो. Groq configured असल्यास सामान्य natural-language questions देखील answer करता येतात.";
        } else {
            if (appealQ) reply="An appeal is available only after your complaint reaches RESOLVED, COMPLETED or CLOSED. Open Appeals or My Complaints and use ‘Submit Appeal’ on the eligible complaint. Enter the reason and optionally attach evidence; it goes to human review.";
            else if (status) reply="Open Track Status and enter your tracking code. My Complaints also shows status, department, assigned worker and SLA.";
            else if (sla) reply="SLA is calculated from the complaint category and priority. Complaint details show the remaining time and breach risk.";
            else if (locationQ) reply="Complaint location is independent of login location. When GPS is provided, the server resolves the complaint's actual pincode and municipal ward and routes the complaint using that location scope.";
            else if (duplicateQ) reply="SamadhanPoint compares a new complaint with existing complaints using text similarity. High similarity is surfaced as a possible duplicate and can be sent for human review.";
            else if (departmentQ) reply="AI can suggest a category, while server-side Java routing rules choose the department using the category and complaint location. The backend remains authoritative.";
            else if (workerQ) reply="Workers receive complaints within their department and ward scope. The workflow is ASSIGNED → IN_PROGRESS → RESOLVED; the citizen verifies the resolution before the complaint becomes CLOSED.";
            else if (notificationQ) reply="Notifications cover complaint submission, routing, worker assignment, status changes, resolution, citizen verification and appeal review.";
            else if (languageQ) reply="SamadhanPoint supports English, Hindi and Marathi. The detected language and confidence are stored with the complaint.";
            else if (aiQ) reply="AI supports language detection, category suggestion, English translation, priority/urgency signals, duplicate detection and human-review signals. Security, permissions and final routing remain controlled by the Java backend.";
            else if (complaintQ) reply="Use New Complaint to submit a grievance. Add the description, location and evidence. The system then detects language, suggests a category, routes it, calculates priority/SLA and checks for possible duplicates.";
            else reply="I can answer questions about SamadhanPoint, including complaint filing, tracking, GPS/ward mapping, departments, workers, SLA, duplicate detection, notifications, appeals and AI triage. With Groq configured, the assistant can also handle general natural-language questions instead of only fixed keywords.";
        }
        return Map.of("success",true,"reply",reply,"modelUsed","samadhanpoint-local-assistant-v4","aiProvider","Local fallback");
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> openAiAssistantChat(String question, String language, Map<String,Object> user) {
        if (groqKey.isBlank()) return null;
        try {
            String role = safe(user, "role", "CITIZEN");
            String roleGuide = switch (role) {
                case "CITIZEN" -> "The user is a citizen. Help with filing complaints, tracking their own complaints, appeals, notifications, evidence and general civic-process questions. Never disclose another citizen's data.";
                case "ADMIN" -> "The user is an administrator. Help with authorized city-wide management, users, departments, complaints, reports, SLA monitoring and audit workflow. Never expose passwords, tokens or unnecessary personal data.";
                case "DEPARTMENT_HEAD" -> "The user is a department head. Help with their assigned department and ward, complaint assignment, SLA, escalation, workers, reports and appeals. Never disclose records outside their authorized scope.";
                case "WORKER" -> "The user is a field worker. Help with their assigned tasks, status updates, evidence, resolution workflow and SLA. Never disclose other workers' private assignments or citizen personal data.";
                case "AUDITOR" -> "The user is an auditor. Help with read-only audit logs, reports, workflow evidence, SLA and compliance. Never perform actions or disclose credentials.";
                default -> "Use only the permissions of the authenticated role.";
            };
            String prompt = "You are the SamadhanPoint AI Assistant for a municipal citizen-grievance system. " +
                    roleGuide + " " +
                    "You are a universal assistant available from every SamadhanPoint panel. Answer the user's actual question directly, even when it is a general or unexpected natural-language question. Do not restrict answers to predefined FAQ keywords. If the question is unrelated to SamadhanPoint, answer it normally unless it requests restricted/private application data. " +
                    "For SamadhanPoint use authoritative rules: roles are CITIZEN, ADMIN, DEPARTMENT_HEAD, WORKER, AUDITOR; complaint location is independent of login location; GPS can resolve pincode and municipal ward; routing is server-authoritative; workers use ASSIGNED -> IN_PROGRESS -> RESOLVED; citizens verify resolved complaints before CLOSED; appeals are available after RESOLVED/COMPLETED/CLOSED and go to human review; Department Heads are scoped to department + ward; Auditors are read-only; supported complaint languages are English, Hindi and Marathi. " +
                    "SECURITY: never reveal, infer, enumerate or summarize another role's private records, passwords, tokens, contact details, complaint details or audit information. Do not claim an action was performed. If the user asks for private data outside their scope, explain that it is restricted and direct them to the authorized panel. If the user asks how to do something, give step-by-step UI instructions. Reply in the requested language when possible. Current user role: " + role + ". Requested language: " + language + ".";

            Map<String,Object> system = new LinkedHashMap<>();
            system.put("role", "system");
            system.put("content", prompt);
            Map<String,Object> userMessage = new LinkedHashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", question);
            Map<String,Object> req = new LinkedHashMap<>();
            req.put("model", groqModel);
            req.put("messages", List.of(system, userMessage));
            req.put("temperature", 0.2);
            req.put("max_tokens", 700);
            String json = mapper.writeValueAsString(req);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + groqKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(25))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            Map<String,Object> root = mapper.readValue(response.body(), new TypeReference<Map<String,Object>>() {});
            Object choicesObj = root.get("choices");
            if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) return null;
            Object first = choices.get(0);
            if (!(first instanceof Map<?,?> fm)) return null;
            Object message = fm.get("message");
            if (!(message instanceof Map<?,?> mm)) return null;
            String outText = safe(toStringMap(mm), "content").trim();
            if (outText.isBlank()) return null;
            return Map.of("reply", outText);
        } catch (Exception ignored) {
            return null;
        }
    }

    public int unreadNotificationCount(Map<String, Object> user) {
        if (user == null) return 0;
        return count("SELECT COUNT(*) FROM sp_notifications WHERE user_id=? AND read_at IS NULL", safe(user,"id"));
    }

    public List<Map<String, Object>> notifications(Map<String, Object> user) {
        if (user == null) return List.of();
        String id = safe(user, "id");
        return db.queryForList("SELECT id,title,message,type,read_at,created_at FROM sp_notifications WHERE user_id=? ORDER BY created_at DESC LIMIT 50", id);
    }

    public Map<String, Object> markNotification(String id, Map<String, Object> user) {
        if (user == null) return error("Unauthorized");
        db.update("UPDATE sp_notifications SET read_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?", id, user.get("id"));
        return Map.of("success", true);
    }

    private void notifyDepartmentHeads(String department, String ward, String title, String message, String type) {
        if (department == null || ward == null) return;
        try {
            for (Map<String, Object> row : db.queryForList("SELECT id FROM sp_users WHERE role='DEPARTMENT_HEAD' AND LOWER(TRIM(department))=LOWER(TRIM(?)) AND LOWER(TRIM(ward_jurisdiction))=LOWER(TRIM(?)) AND blocked=false", department, ward)) {
                notifyUser(safe(row, "id"), title, message, type);
            }
        } catch (Exception ignored) { }
    }

    private void notifyRole(String role, String title, String message, String type) {
        try {
            for (Map<String, Object> row : db.queryForList("SELECT id FROM sp_users WHERE role=? AND blocked=false", role)) {
                notifyUser(safe(row, "id"), title, message, type);
            }
        } catch (Exception ignored) { }
    }

    private void notifyUser(String userId, String title, String message, String type) {
        if (userId == null || userId.isBlank()) return;
        try {
            db.update("INSERT INTO sp_notifications(id,user_id,title,message,type) VALUES(?,?,?,?,?)",
                UUID.randomUUID().toString(), userId, title, message, type);
        } catch (Exception ignored) { }
    }

    private void audit(Map<String, Object> actor, String entityId, String action, Map<?, ?> details) {
        try {
            String actorId = actor == null ? null : safe(actor, "id");
            db.update("INSERT INTO sp_audit_logs(actor_id,action,entity_type,entity_id,details) VALUES(?,?,?,?,?::jsonb)",
                actorId, action, "GRIEVANCE", entityId, jsonSafe(details));
        } catch (Exception ignored) { }
    }

    private Map<String, Object> error(String message) {
        return Map.of("success", false, "error", message == null ? "Request failed." : message);
    }

    private boolean isRole(Map<String, Object> user, String role) {
        return user != null && role != null && role.equals(safe(user, "role"));
    }

    private boolean isAnyRole(Map<String, Object> user, String... roles) {
        if (user == null || roles == null) return false;
        String r = safe(user, "role");
        for (String x : roles) if (x != null && x.equals(r)) return true;
        return false;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(Map<?, ?> map, String key) { return safe(map, key, ""); }
    private String safe(Map<?, ?> map, String key, String fallback) { if (map == null) return fallback; Object v = map.get(key); return v == null ? fallback : String.valueOf(v); }
    private String safe(String value) { return value == null ? "" : value; }
    private String safe(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private String nullable(Map<String, Object> map, String key) { Object v = map == null ? null : map.get(key); return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v); }
    private int integer(Map<String, Object> map, String key, int fallback) { try { return Integer.parseInt(safe(map, key, String.valueOf(fallback))); } catch (Exception e) { return fallback; } }
    private double number(Map<String, Object> map, String key, double fallback) { Object v = map == null ? null : map.get(key); if (v instanceof Number n) return n.doubleValue(); try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return fallback; } }
    @SuppressWarnings("unchecked") private Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }

    private Map<String,Object> toStringMap(Map<?,?> source){ Map<String,Object> out=new LinkedHashMap<>(); if(source!=null) source.forEach((k,v)->out.put(String.valueOf(k),v)); return out; }
    private Double numberObject(Object value){ if(value instanceof Number n) return n.doubleValue(); try{return value==null?null:Double.parseDouble(String.valueOf(value));}catch(Exception e){return null;} }

    private String jsonSafe(Object value) { try { return mapper.writeValueAsString(value == null ? Map.of() : value); } catch (Exception e) { return "{}"; } }

    private String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
