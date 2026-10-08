CREATE TABLE IF NOT EXISTS sp_departments (
 id VARCHAR(80) PRIMARY KEY, code VARCHAR(80) UNIQUE NOT NULL, name VARCHAR(255) NOT NULL, sla_hours INTEGER NOT NULL DEFAULT 48, active BOOLEAN DEFAULT TRUE
);
CREATE TABLE IF NOT EXISTS sp_users (
 id VARCHAR(128) PRIMARY KEY, username VARCHAR(128) UNIQUE NOT NULL, email VARCHAR(255) UNIQUE NOT NULL,
 password_value VARCHAR(255), full_name VARCHAR(255) NOT NULL, phone VARCHAR(50), role VARCHAR(64) NOT NULL,
 department VARCHAR(255), ward_jurisdiction VARCHAR(255), preferred_language VARCHAR(20) DEFAULT 'en', address TEXT, pincode VARCHAR(20), zone VARCHAR(100),
 blocked BOOLEAN DEFAULT FALSE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, last_login_at TIMESTAMP
);
CREATE TABLE IF NOT EXISTS sp_grievances (
 id VARCHAR(128) PRIMARY KEY, tracking_code VARCHAR(64) UNIQUE NOT NULL, user_id VARCHAR(128), citizen_name VARCHAR(255) NOT NULL,
 contact_email VARCHAR(255), ward VARCHAR(255) NOT NULL, city VARCHAR(128) DEFAULT 'Mumbai', pincode VARCHAR(20), street_address TEXT,
 locality VARCHAR(255), landmark VARCHAR(255), category VARCHAR(128) NOT NULL, description TEXT NOT NULL, detected_language VARCHAR(20),
 language_confidence DOUBLE PRECISION, english_translation TEXT, category_confidence DOUBLE PRECISION, assigned_department VARCHAR(255),
 assigned_worker_id VARCHAR(128), assigned_worker_name VARCHAR(255), urgency VARCHAR(32), priority VARCHAR(32), status VARCHAR(64),
 sla_hours INTEGER, predicted_breach_probability DOUBLE PRECISION, duplicate_of_id VARCHAR(128), similarity_score DOUBLE PRECISION,
 is_emergency BOOLEAN DEFAULT FALSE, before_evidence TEXT, after_evidence TEXT, resolution_notes TEXT, appeal_status VARCHAR(64),
 appeal_reason TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, resolved_at TIMESTAMP,
 data JSONB DEFAULT '{}'::jsonb
);
CREATE TABLE IF NOT EXISTS sp_complaint_updates (
 id VARCHAR(128) PRIMARY KEY, grievance_id VARCHAR(128) NOT NULL, actor_id VARCHAR(128), status VARCHAR(64), note TEXT, evidence TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS sp_appeals (
 id VARCHAR(128) PRIMARY KEY, grievance_id VARCHAR(128) NOT NULL, citizen_id VARCHAR(128), reason TEXT NOT NULL,
 evidence TEXT, status VARCHAR(64) DEFAULT 'SUBMITTED', reviewer_id VARCHAR(128), reviewer_note TEXT,
 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS sp_notifications (
 id VARCHAR(128) PRIMARY KEY, user_id VARCHAR(128) NOT NULL, title VARCHAR(255) NOT NULL, message TEXT NOT NULL,
 type VARCHAR(64) DEFAULT 'INFO', read_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS sp_audit_logs (
 id BIGSERIAL PRIMARY KEY, actor_id VARCHAR(128), action VARCHAR(128), entity_type VARCHAR(128), entity_id VARCHAR(128),
 details JSONB DEFAULT '{}'::jsonb, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, ip_address VARCHAR(64)
);
CREATE TABLE IF NOT EXISTS sp_pincode_wards (
 pincode VARCHAR(20) PRIMARY KEY, ward VARCHAR(255) NOT NULL, zone VARCHAR(255) NOT NULL, locality VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS sp_wards (
 id VARCHAR(64) PRIMARY KEY, code VARCHAR(64) UNIQUE NOT NULL, name VARCHAR(255) UNIQUE NOT NULL, zone VARCHAR(100) NOT NULL, active BOOLEAN DEFAULT TRUE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sp_wards_zone ON sp_wards(zone);

CREATE TABLE IF NOT EXISTS sp_admin_preferences (
 user_id VARCHAR(128) PRIMARY KEY, widget_order JSONB NOT NULL DEFAULT '[]'::jsonb, pinned_widgets JSONB NOT NULL DEFAULT '[]'::jsonb, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sp_password_otps (
 id VARCHAR(128) PRIMARY KEY, user_id VARCHAR(128) NOT NULL, otp_hash VARCHAR(255) NOT NULL,
 expires_at TIMESTAMP NOT NULL, used BOOLEAN DEFAULT FALSE, attempts INTEGER DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sp_password_otps_user ON sp_password_otps(user_id,expires_at);

ALTER TABLE sp_departments ADD COLUMN IF NOT EXISTS active BOOLEAN DEFAULT TRUE;
ALTER TABLE sp_users ADD COLUMN IF NOT EXISTS address TEXT;
ALTER TABLE sp_users ADD COLUMN IF NOT EXISTS pincode VARCHAR(20);
ALTER TABLE sp_users ADD COLUMN IF NOT EXISTS zone VARCHAR(100);
ALTER TABLE sp_grievances ADD COLUMN IF NOT EXISTS assigned_worker_id VARCHAR(128);
ALTER TABLE sp_grievances ADD COLUMN IF NOT EXISTS assigned_worker_name VARCHAR(255);
ALTER TABLE sp_grievances ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION;
ALTER TABLE sp_grievances ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;
ALTER TABLE sp_grievances ADD COLUMN IF NOT EXISTS location_accuracy DOUBLE PRECISION;
ALTER TABLE sp_grievances ADD COLUMN IF NOT EXISTS before_evidence TEXT;
ALTER TABLE sp_grievances ADD COLUMN IF NOT EXISTS after_evidence TEXT;
-- Compatibility migration for older builds that accidentally created evidence columns as JSONB.
ALTER TABLE sp_grievances ALTER COLUMN before_evidence TYPE TEXT USING before_evidence::text;
ALTER TABLE sp_grievances ALTER COLUMN after_evidence TYPE TEXT USING after_evidence::text;
ALTER TABLE sp_grievances ADD COLUMN IF NOT EXISTS resolution_notes TEXT;
ALTER TABLE sp_grievances ADD COLUMN IF NOT EXISTS appeal_status VARCHAR(64);
ALTER TABLE sp_grievances ADD COLUMN IF NOT EXISTS appeal_reason TEXT;
ALTER TABLE sp_pincode_wards ADD COLUMN IF NOT EXISTS locality VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_sp_grievances_user ON sp_grievances(user_id);
CREATE INDEX IF NOT EXISTS idx_sp_grievances_worker ON sp_grievances(assigned_worker_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_sp_department_head_scope ON sp_users(department,ward_jurisdiction) WHERE role='DEPARTMENT_HEAD' AND blocked=false AND ward_jurisdiction IS NOT NULL AND ward_jurisdiction <> 'ALL_WARDS';
CREATE INDEX IF NOT EXISTS idx_sp_grievances_department ON sp_grievances(assigned_department);
CREATE INDEX IF NOT EXISTS idx_sp_grievances_status ON sp_grievances(status);
CREATE INDEX IF NOT EXISTS idx_sp_grievances_language ON sp_grievances(detected_language);
CREATE INDEX IF NOT EXISTS idx_sp_appeals_grievance ON sp_appeals(grievance_id);
CREATE INDEX IF NOT EXISTS idx_sp_notifications_user ON sp_notifications(user_id,created_at);
CREATE INDEX IF NOT EXISTS idx_sp_audit_created ON sp_audit_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_sp_updates_grievance ON sp_complaint_updates(grievance_id,created_at);


INSERT INTO sp_wards(id,code,name,zone,active) VALUES
('W01','A','Ward A','Zone 1',true),('W02','B','Ward B','Zone 1',true),('W03','C','Ward C','Zone 2',true),('W04','D','Ward D','Zone 2',true),('W05','E','Ward E','Zone 1',true),
('W06','F-N','Ward F North','Zone 1',true),('W07','F-S','Ward F South','Zone 1',true),('W08','G-N','Ward G North','Zone 1',true),('W09','G-S','Ward G South','Zone 1',true),
('W10','H-E','Ward H East','Zone 2',true),('W11','H-W','Ward H West','Zone 2',true),('W12','K-E','Ward K East','Zone 2',true),('W13','K-W','Ward K West','Zone 2',true),('W14','L','Ward L','Zone 2',true),
('W15','M-E','Ward M East','Zone 3',true),('W16','M-W','Ward M West','Zone 2',true),('W17','N','Ward N','Zone 3',true),('W18','P-N','Ward P North','Zone 3',true),('W19','P-S','Ward P South','Zone 3',true),
('W20','R-C','Ward R Central','Zone 3',true),('W21','R-N','Ward R North','Zone 3',true),('W22','R-S','Ward R South','Zone 3',true),('W23','S','Ward S','Zone 3',true),('W24','T','Ward T','Zone 3',true)
ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code,name=EXCLUDED.name,zone=EXCLUDED.zone,active=EXCLUDED.active;

INSERT INTO sp_departments(id,code,name,sla_hours,active) VALUES
('D1','WATER','Department of Hydraulic Engineering & Water Supply',48,true),
('D2','ROADS','Department of Roads & Traffic Infrastructure',72,true),
('D3','WASTE','Department of Solid Waste Management',24,true),
('D4','LIGHT','Department of Electrical & Public Lighting',72,true),
('D5','HEALTH','Department of Public Health & Sanitation',48,true),
('D6','TRANSPORT','Department of Municipal Transport & Undertakings',96,true),
('D7','SANITATION','Department of Sewerage & Drainage Operations',36,true),
('D8','OTHER','General Citizen Grievance Cell',72,true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO sp_pincode_wards(pincode,ward,zone,locality) VALUES
('400001','Ward A','Zone 1','Fort / South Mumbai'),('400002','Ward A','Zone 1','Kalbadevi'),('400003','Ward A','Zone 1','Masjid Bunder'),('400004','Ward A','Zone 1','Girgaon'),('400005','Ward A','Zone 1','Colaba'),
('400062','Ward B','Zone 1','Goregaon West'),('400063','Ward B','Zone 1','Goregaon East / Demo Malad-Goregaon'),('400064','Ward B','Zone 1','Malad West'),('400067','Ward B','Zone 1','Kandivali West'),('400068','Ward B','Zone 1','Dahisar'),('400069','Ward B','Zone 1','Andheri East'),('400101','Ward B','Zone 1','Kandivali East'),
('400070','Ward C','Zone 2','Kurla'),('400071','Ward C','Zone 2','Chembur'),('400072','Ward C','Zone 2','Sakinaka'),('400077','Ward C','Zone 2','Ghatkopar East'),
('400076','Ward D','Zone 2','Powai'),('400078','Ward D','Zone 2','Vikhroli'),('400079','Ward D','Zone 2','Mulund'),('400080','Ward D','Zone 2','Mulund West')
ON CONFLICT (pincode) DO UPDATE SET ward=EXCLUDED.ward,zone=EXCLUDED.zone,locality=EXCLUDED.locality;

-- Expanded Mumbai administrative ward directory (demo routing data; pincode -> primary ward/locality).
INSERT INTO sp_pincode_wards(pincode,ward,zone,locality) VALUES
('400006','Ward D','Zone 1','Malabar Hill'),('400007','Ward D','Zone 1','Grant Road / Tardeo'),('400008','Ward E','Zone 1','Mumbai Central'),
('400009','Ward B','Zone 1','Chinchbunder'),('400010','Ward B','Zone 1','Mazgaon'),('400011','Ward E','Zone 1','Byculla'),('400012','Ward F South','Zone 1','Parel'),
('400013','Ward F South','Zone 1','Delisle Road'),('400014','Ward G North','Zone 1','Dadar East'),('400015','Ward F South','Zone 1','Sewri'),('400016','Ward G North','Zone 1','Mahim'),
('400017','Ward G North','Zone 1','Dharavi'),('400018','Ward G South','Zone 1','Worli'),('400019','Ward F North','Zone 1','Matunga'),('400020','Ward A','Zone 1','Marine Lines'),
('400021','Ward A','Zone 1','Nariman Point'),('400022','Ward F North','Zone 1','Sion'),('400023','Ward A','Zone 1','Fort'),('400024','Ward L','Zone 2','Nehru Nagar / Kurla'),
('400025','Ward G South','Zone 1','Prabhadevi'),('400026','Ward D','Zone 1','Cumbala Hill'),('400027','Ward E','Zone 1','Jijamata Udyan'),('400028','Ward G North','Zone 1','Dadar West'),
('400029','Ward K East','Zone 2','Vile Parle East / Airport'),('400030','Ward G South','Zone 1','Worli / Prabhadevi'),('400031','Ward F North','Zone 1','Wadala'),('400032','Ward A','Zone 1','Mantralaya'),
('400033','Ward B','Zone 1','Tank Road'),('400034','Ward D','Zone 1','Tardeo / Tulsiwadi'),('400035','Ward D','Zone 1','Raj Bhavan'),('400036','Ward D','Zone 1','A K Marg'),
('400037','Ward F North','Zone 1','Antop Hill'),('400038','Ward A','Zone 1','Ballard Estate'),('400039','Ward A','Zone 1','Fort'),('400050','Ward H West','Zone 2','Bandra West'),
('400051','Ward H East','Zone 2','Bandra East'),('400052','Ward H West','Zone 2','Khar West'),('400053','Ward K West','Zone 2','Andheri West'),('400054','Ward H West','Zone 2','Santacruz West'),
('400055','Ward H East','Zone 2','Santacruz East'),('400056','Ward K East','Zone 2','Vile Parle East'),('400057','Ward K East','Zone 2','Vile Parle East'),('400058','Ward K West','Zone 2','Andheri West'),
('400059','Ward K East','Zone 2','Andheri East'),('400060','Ward K East','Zone 2','Jogeshwari East'),('400061','Ward K West','Zone 2','Versova / Andheri West'),('400063','Ward P South','Zone 3','Goregaon East / Jogeshwari East'),
('400064','Ward P South','Zone 3','Malad West'),('400065','Ward P North','Zone 3','Aarey / Goregaon East'),('400066','Ward P North','Zone 3','Borivali East / Goregaon East'),('400067','Ward R South','Zone 3','Kandivali West'),
('400068','Ward R North','Zone 3','Dahisar'),('400069','Ward K East','Zone 2','Andheri East'),('400070','Ward L','Zone 2','Kurla'),('400071','Ward M West','Zone 2','Chembur'),
('400072','Ward L','Zone 2','Sakinaka'),('400074','Ward M East','Zone 3','Chembur East / Wadala'),('400075','Ward N','Zone 3','Ghatkopar'),('400076','Ward S','Zone 3','Powai'),
('400077','Ward N','Zone 3','Ghatkopar East'),('400078','Ward S','Zone 3','Vikhroli'),('400079','Ward S','Zone 3','Vikhroli East'),('400080','Ward T','Zone 3','Mulund West'),
('400081','Ward T','Zone 3','Mulund East'),('400082','Ward N','Zone 3','Ghatkopar'),('400083','Ward S','Zone 3','Bhandup'),('400084','Ward N','Zone 3','Ghatkopar'),
('400086','Ward N','Zone 3','Ghatkopar West'),('400088','Ward M East','Zone 3','Mankhurd'),('400089','Ward M West','Zone 2','Chembur'),('400093','Ward K East','Zone 2','Andheri East'),
('400094','Ward P North','Zone 3','Goregaon East'),('400095','Ward P South','Zone 3','Malad West'),('400096','Ward P North','Zone 3','Malad East'),('400097','Ward P North','Zone 3','Malad East'),
('400098','Ward K East','Zone 2','Vile Parle East'),('400099','Ward K East','Zone 2','Andheri East'),('400101','Ward R South','Zone 3','Kandivali East')
ON CONFLICT (pincode) DO UPDATE SET ward=EXCLUDED.ward,zone=EXCLUDED.zone,locality=EXCLUDED.locality;
