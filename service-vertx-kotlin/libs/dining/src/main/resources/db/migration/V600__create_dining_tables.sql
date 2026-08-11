-- =====================================================
-- 膳食营养模块 - 核心表结构（Aceso 长者餐食管理）
-- Schema: dining
-- 号段：V600-V699（2026 划归 Aceso 餐饮领域，见根 AGENTS.md）
-- 业务枚举一律使用中文值：餐食类型/菜品分类/餐次/饮食标签/就餐状态等，
-- 不引入英文业务 code。
-- =====================================================

CREATE SCHEMA IF NOT EXISTS dining;
SET search_path TO dining, public;

-- 长者饮食档案：关联 healthcare.patients（长者）与
-- healthcare.encounters（ELDERLY_CARE 入住周期），入住建档即生效，
-- 离院后通过"仅纳入在院入住"的配餐口径自然停用。
CREATE TABLE dining_diet_profiles (
    id                  VARCHAR(32) PRIMARY KEY,
    patient_id          VARCHAR(32) NOT NULL REFERENCES healthcare.patients(id),
    encounter_id        VARCHAR(32) NOT NULL REFERENCES healthcare.encounters(id),
    meal_type           VARCHAR NOT NULL,              -- 普食/软食/碎食/流食/糖尿病餐
    allergies           JSONB DEFAULT '[]',            -- 忌口/过敏明细（字符串数组）
    portion_preference  VARCHAR,                       -- 份量偏好：标准/大半份/小半份
    remark              TEXT,
    status              VARCHAR DEFAULT '启用',         -- 启用/停用
    metadata            JSONB,
    created_at          TIMESTAMPTZ DEFAULT now(),
    updated_at          TIMESTAMPTZ DEFAULT now()
);
-- 同一长者同一时间只允许一份启用档案（与 V501 活动入住唯一约束口径一致）
CREATE UNIQUE INDEX uq_dining_diet_profiles_active_patient
    ON dining_diet_profiles (patient_id) WHERE status = '启用';
CREATE INDEX idx_dining_diet_profiles_encounter ON dining_diet_profiles (encounter_id);
CREATE INDEX idx_dining_diet_profiles_status ON dining_diet_profiles (status);

-- 菜品库
CREATE TABLE dining_dishes (
    id              VARCHAR(32) PRIMARY KEY,
    name            VARCHAR NOT NULL,
    category        VARCHAR NOT NULL,                  -- 荤菜/素菜/汤品/主食/加餐
    meal_times      JSONB DEFAULT '[]',                -- 适用餐次：["早餐","午餐","晚餐","加餐"]
    diet_tags       JSONB DEFAULT '[]',                -- 饮食标签：["低盐","低糖","无糖","清真","高蛋白","少油","无辣"]
    status          VARCHAR DEFAULT '启用',             -- 启用/停用
    remark          TEXT,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_dining_dishes_category ON dining_dishes (category);
CREATE INDEX idx_dining_dishes_status ON dining_dishes (status);

-- 周菜谱（机构级，按周编排；每周最多一份启用菜谱，停用后可换版）
CREATE TABLE dining_weekly_menus (
    id              VARCHAR(32) PRIMARY KEY,
    week_start      DATE NOT NULL,                     -- 周起始日（周一）
    name            VARCHAR,
    status          VARCHAR DEFAULT '启用',             -- 启用/停用
    remark          TEXT,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
CREATE UNIQUE INDEX uq_dining_weekly_menus_active_week
    ON dining_weekly_menus (week_start) WHERE status = '启用';
CREATE INDEX idx_dining_weekly_menus_week ON dining_weekly_menus (week_start);

-- 周菜谱明细：周几 × 餐次 × 菜品；dish_name 为创建时快照，
-- 菜品改名/停用不影响历史菜谱展示。
CREATE TABLE dining_weekly_menu_items (
    id              VARCHAR(32) PRIMARY KEY,
    menu_id         VARCHAR(32) NOT NULL REFERENCES dining_weekly_menus(id) ON DELETE CASCADE,
    day_of_week     INT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),  -- 1=周一 ... 7=周日
    meal_time       VARCHAR NOT NULL,                  -- 早餐/午餐/晚餐/加餐
    dish_id         VARCHAR(32) NOT NULL REFERENCES dining_dishes(id),
    dish_name       VARCHAR NOT NULL,
    sort_order      INT DEFAULT 0,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_dining_weekly_menu_items_menu ON dining_weekly_menu_items (menu_id);
CREATE UNIQUE INDEX uq_dining_weekly_menu_items_slot
    ON dining_weekly_menu_items (menu_id, day_of_week, meal_time, dish_id);

-- 配餐名单：按「日期 + 餐次」唯一生成
CREATE TABLE dining_rosters (
    id              VARCHAR(32) PRIMARY KEY,
    menu_date       DATE NOT NULL,
    meal_time       VARCHAR NOT NULL,                  -- 早餐/午餐/晚餐/加餐
    generated_by    VARCHAR,
    generated_at    TIMESTAMPTZ,
    remark          TEXT,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
CREATE UNIQUE INDEX uq_dining_rosters_meal ON dining_rosters (menu_date, meal_time);

-- 配餐名单条目：长者与餐食类型/忌口快照；一名长者一餐次一条。
-- source 自动=按饮食档案生成，手工=人工增删调整；
-- adjust_type 外出/请假=该餐不就餐（配餐口径排除），临时加餐=额外增加。
CREATE TABLE dining_roster_items (
    id              VARCHAR(32) PRIMARY KEY,
    roster_id       VARCHAR(32) NOT NULL REFERENCES dining_rosters(id) ON DELETE CASCADE,
    patient_id      VARCHAR(32) NOT NULL REFERENCES healthcare.patients(id),
    encounter_id    VARCHAR(32),
    patient_name    VARCHAR NOT NULL,
    meal_type       VARCHAR NOT NULL,
    allergies       JSONB DEFAULT '[]',
    source          VARCHAR NOT NULL,                  -- 自动/手工
    adjust_type     VARCHAR,                           -- 外出/请假/临时加餐
    remark          TEXT,
    sort_order      INT DEFAULT 0,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
CREATE UNIQUE INDEX uq_dining_roster_items_patient
    ON dining_roster_items (roster_id, patient_id);
CREATE INDEX idx_dining_roster_items_roster ON dining_roster_items (roster_id);

-- 就餐执行登记：同一名单条目（= 同一长者同一餐次）幂等更新
CREATE TABLE dining_meal_executions (
    id              VARCHAR(32) PRIMARY KEY,
    roster_item_id  VARCHAR(32) NOT NULL UNIQUE REFERENCES dining_roster_items(id) ON DELETE CASCADE,
    status          VARCHAR NOT NULL,                  -- 正常/部分/未就餐/拒食
    remark          TEXT,
    recorded_by     VARCHAR NOT NULL,                  -- 登记人（认证主体）
    recorded_at     TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now(),
    metadata        JSONB
);
CREATE INDEX idx_dining_meal_executions_roster_item ON dining_meal_executions (roster_item_id);
