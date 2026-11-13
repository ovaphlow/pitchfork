export interface Messages {
  meta: {
    title: string;
    description: string;
  };
  brand: {
    name: string;
    mark: string;
    home: string;
  };
  language: {
    toggleText: string;
    toggleLabel: string;
  };
  common: {
    signOut: string;
    checkingSession: string;
    yourAccount: string;
  };
  identity: {
    label: string;
    jobSeeker: string;
    recruiter: string;
  };
  nav: {
    label: string;
    home: string;
    search: string;
    messages: string;
    profile: string;
  };
  search: {
    heading: string;
    copy: string;
    keyword: string;
    keywordPlaceholder: string;
    city: string;
    cityPlaceholder: string;
    submit: string;
    working: string;
    idle: string;
    results: string;
    empty: string;
    failed: string;
    truncated: (count: number) => string;
  };
  messages: {
    heading: string;
    copy: string;
    empty: string;
    hint: string;
  };
  profile: {
    heading: string;
    name: string;
    identifier: string;
    subject: string;
    identity: string;
    identityHint: string;
  };
  positions: {
    toggle: string;
    collapse: string;
    loading: string;
    failed: string;
    empty: string;
    create: string;
    createHeading: string;
    createHint: string;
    createSubmit: string;
    createSubmitting: string;
    cancel: string;
    created: string;
    notVerified: string;
    publish: string;
    pause: string;
    resume: string;
    close: string;
    actionFailed: string;
    headcountValue: (count: number) => string;
    fields: {
      title: string;
      titlePlaceholder: string;
      employmentType: string;
      workMode: string;
      headcount: string;
      salary: string;
      negotiable: string;
      salaryMin: string;
      salaryMax: string;
      salaryUnit: string;
      education: string;
      experience: string;
      city: string;
      cityPlaceholder: string;
      description: string;
      descriptionPlaceholder: string;
      requirements: string;
      requirementsPlaceholder: string;
    };
    errors: {
      title: string;
      headcount: string;
      salaryMin: string;
      salaryRange: string;
    };
  };
  workspace: {
    seekerHeading: string;
    seekerCopy: string;
    recruiterHeading: string;
    recruiterCopy: string;
    organizations: (count: number) => string;
    loading: string;
    failed: string;
    empty: string;
    emptyHint: string;
    positionsCount: (count: number) => string;
    positionsLoading: string;
    positionsFailed: string;
    positionsEmpty: string;
    loadMore: string;
    loadingMore: string;
    salaryNegotiable: string;
    salaryRange: (min: number | null, max: number | null, unit: string) => string;
    createAction: string;
    createHeading: string;
    createHint: string;
    createSubmit: string;
    createSubmitting: string;
    createCancel: string;
    created: string;
    pendingHint: string;
    fields: {
      name: string;
      namePlaceholder: string;
      orgType: string;
      shortName: string;
      shortNamePlaceholder: string;
      creditCode: string;
      creditCodePlaceholder: string;
      license: string;
      licenseHelper: string;
    };
    createErrors: {
      name: string;
      creditCode: string;
      license: string;
      fileSize: string;
    };
  };
  hero: {
    line1: string;
    line2Prefix: string;
    accent: string;
    line2Suffix: string;
    signedIn: (name: string) => string;
  };
  status: {
    heading: string;
    phase: {
      checking: string;
      online: string;
      offline: string;
    };
    checkedAt: (time: string) => string;
    http: string;
    database: string;
    latency: string;
    ready: string;
    notReady: string;
    endpoint: string;
    notConfigured: string;
    reported: (status: string) => string;
    openEndpoint: string;
    probe: string;
    probing: string;
    latencyValue: (milliseconds: number) => string;
  };
  stack: {
    heading: string;
    shell: string;
    surface: string;
    backend: string;
    shellValue: string;
    surfaceValue: string;
    backendValue: string;
  };
  access: {
    heading: string;
    roles: (count: number) => string;
    empty: string;
    unavailable: string;
  };
  footer: {
    tagline: string;
    stack: string;
  };
  auth: {
    eyebrow: string;
    loginHeading: string;
    registerHeading: string;
    loginSubcopyJobSeeker: string;
    loginSubcopyRecruiter: string;
    registerSubcopyJobSeeker: string;
    registerSubcopyRecruiter: string;
    displayName: string;
    displayNamePlaceholder: string;
    email: string;
    emailPlaceholder: string;
    accountOrEmail: string;
    identifierPlaceholder: string;
    password: string;
    sendLink: string;
    noticeLinkSent: string;
    usePasswordLogin: string;
    useEmailLinkLogin: string;
    linkHeading: string;
    linkSubcopy: string;
    continue: string;
    linkInvalid: string;
    backToLogin: string;
    login: string;
    signUp: string;
    working: string;
    switchToRegister: string;
    switchToLogin: string;
    errors: {
      displayName: string;
      email: string;
      account: string;
      password: string;
    };
  };
  errors: {
    notConfigured: string;
    network: (origin: string) => string;
    authStatus: (status: number) => string;
    requestFailed: (status: number) => string;
    statusHttp: (status: number) => string;
    statusPayload: string;
    unknown: string;
  };
}

export const zhCN: Messages = {
  meta: {
    title: "栖木",
    description: "基于 Astro 与 Svelte 客户端渲染、连接 Go API 的移动工作区。",
  },
  brand: {
    name: "栖木",
    mark: "栖",
    home: "栖木首页",
  },
  language: {
    toggleText: "EN",
    toggleLabel: "切换到英文",
  },
  common: {
    signOut: "退出登录",
    checkingSession: "正在检查登录状态…",
    yourAccount: "你的账号",
  },
  identity: {
    label: "登录身份",
    jobSeeker: "求职者",
    recruiter: "招聘方",
  },
  nav: {
    label: "主导航",
    home: "首页",
    search: "搜索",
    messages: "消息",
    profile: "我的",
  },
  search: {
    heading: "搜索岗位",
    copy: "按关键词与城市查找在招岗位。",
    keyword: "关键词",
    keywordPlaceholder: "岗位名称、机构",
    city: "城市",
    cityPlaceholder: "例如 上海",
    submit: "搜索",
    working: "搜索中…",
    idle: "输入关键词或城市后开始搜索。",
    results: "搜索结果",
    empty: "没有匹配的在招岗位。",
    failed: "搜索失败。",
    truncated: (count) => `仅展示前 ${count} 条，继续缩小范围可看到更多。`,
  },
  messages: {
    heading: "消息",
    copy: "机构审核、岗位动态等通知会出现在这里。",
    empty: "还没有消息。",
    hint: "站内消息与通知尚未接入。",
  },
  profile: {
    heading: "我的",
    name: "显示名",
    identifier: "账号",
    subject: "主体标识",
    identity: "登录身份",
    identityHint: "登录身份在登录时选定；需要切换请先退出登录。",
  },
  positions: {
    toggle: "岗位",
    collapse: "收起岗位",
    loading: "正在读取岗位…",
    failed: "读取岗位失败。",
    empty: "还没有岗位，先发布一个吧。",
    create: "发布岗位",
    createHeading: "发布岗位",
    createHint: "岗位先保存为草稿；机构认证通过后可以发布为「招聘中」。",
    createSubmit: "保存草稿",
    createSubmitting: "保存中…",
    cancel: "取消",
    created: "岗位已保存为草稿。",
    notVerified: "机构未认证，暂不能发布岗位。",
    publish: "发布",
    pause: "暂停",
    resume: "恢复发布",
    close: "关闭",
    actionFailed: "操作失败，请稍后再试。",
    headcountValue: (count) => `招 ${count} 人`,
    fields: {
      title: "岗位名称",
      titlePlaceholder: "例如 护士 / 前端工程师",
      employmentType: "用工类型",
      workMode: "工作方式",
      headcount: "招聘人数",
      salary: "薪资",
      negotiable: "面议",
      salaryMin: "薪资下限",
      salaryMax: "薪资上限",
      salaryUnit: "单位",
      education: "学历要求",
      experience: "经验要求",
      city: "城市",
      cityPlaceholder: "例如 上海",
      description: "岗位职责",
      descriptionPlaceholder: "日常工作内容",
      requirements: "任职要求",
      requirementsPlaceholder: "学历、证书、技能等",
    },
    errors: {
      title: "请输入岗位名称。",
      headcount: "招聘人数至少为 1。",
      salaryMin: "请输入薪资下限。",
      salaryRange: "薪资上限不能低于下限。",
    },
  },
  workspace: {
    seekerHeading: "求职者工作台",
    seekerCopy: "下列岗位均为招聘中且未过期的在招岗位。",
    recruiterHeading: "招聘方工作台",
    recruiterCopy: "在有机构成员身份的地方维护机构档案与在招岗位。",
    organizations: (count) => `${count} 个机构`,
    loading: "正在读取所属机构…",
    failed: "读取所属机构失败。",
    empty: "你还没有所属机构。",
    emptyHint: "创建机构并通过平台认证后，才能发布岗位。",
    positionsCount: (count) => `${count} 个在招岗位`,
    positionsLoading: "正在读取在招岗位…",
    positionsFailed: "读取在招岗位失败。",
    positionsEmpty: "暂时没有在招岗位。",
    loadMore: "加载更多",
    loadingMore: "加载中…",
    salaryNegotiable: "面议",
    salaryRange: (min, max, unit) => {
      const suffix = unit ? `元/${unit}` : "元";
      if (min !== null && max !== null) return `${min}–${max} ${suffix}`;
      if (min !== null) return `${min} ${suffix}起`;
      if (max !== null) return `${max} ${suffix}以内`;
      return "";
    },
    createAction: "创建机构",
    createHeading: "创建机构",
    createHint: "提交后你将成为机构所有者；平台审核通过后即可发布岗位。",
    createSubmit: "提交审核",
    createSubmitting: "提交中…",
    createCancel: "取消",
    created: "已提交，等待平台审核。",
    pendingHint: "机构认证通过后才能发布岗位。",
    fields: {
      name: "机构名称",
      namePlaceholder: "营业执照上的机构全称",
      orgType: "机构类型",
      shortName: "机构简称",
      shortNamePlaceholder: "选填",
      creditCode: "统一社会信用代码",
      creditCodePlaceholder: "18 位代码",
      license: "营业执照",
      licenseHelper: "支持 JPG、PNG、WebP、PDF，不超过 8 MB。",
    },
    createErrors: {
      name: "请输入机构名称。",
      creditCode: "请输入统一社会信用代码。",
      license: "请上传营业执照。",
      fileSize: "营业执照不能超过 8 MB。",
    },
  },
  hero: {
    line1: "栖木之上，",
    line2Prefix: "从容",
    accent: "组装",
    line2Suffix: "。",
    signedIn: (name) => `已登录：${name}。移动工作区。`,
  },
  status: {
    heading: "接口状态",
    phase: {
      checking: "检测中",
      online: "在线",
      offline: "离线",
    },
    checkedAt: (time) => `检查于 ${time}`,
    http: "HTTP",
    database: "数据库",
    latency: "延迟",
    ready: "就绪",
    notReady: "未就绪",
    endpoint: "地址",
    notConfigured: "未配置",
    reported: (status) => `接口返回状态“${status}”。`,
    openEndpoint: "打开状态接口",
    probe: "检测接口",
    probing: "检测中…",
    latencyValue: (milliseconds) => `${milliseconds} 毫秒`,
  },
  stack: {
    heading: "技术栈",
    shell: "外壳",
    surface: "界面",
    backend: "后端",
    shellValue: "Astro 静态",
    surfaceValue: "Svelte 客户端",
    backendValue: "Go / PostgreSQL",
  },
  access: {
    heading: "权限",
    roles: (count) => `${count} 个角色`,
    empty: "尚未分配基础数据角色。",
    unavailable: "基础数据暂不可用；登录仍可正常使用。",
  },
  footer: {
    tagline: "栖木 / 客户端渲染",
    stack: "Tailwind 4 · Astro · Svelte 5",
  },
  auth: {
    eyebrow: "栖木账号",
    loginHeading: "登录后继续。",
    registerHeading: "创建你的账号。",
    loginSubcopyJobSeeker: "登录后浏览在招岗位、投递简历。",
    loginSubcopyRecruiter: "登录后管理所属机构与在招岗位。",
    registerSubcopyJobSeeker: "一个账号，开始找工作。",
    registerSubcopyRecruiter: "一个账号，开始发布岗位。",
    displayName: "显示名称",
    displayNamePlaceholder: "你的称呼",
    email: "邮箱",
    emailPlaceholder: "you@example.com",
    accountOrEmail: "账号或邮箱",
    identifierPlaceholder: "name@example.com",
    password: "密码",
    sendLink: "发送登录链接",
    noticeLinkSent: "如果该邮箱已注册，登录链接已发送。请查收邮件。",
    usePasswordLogin: "使用密码登录",
    useEmailLinkLogin: "使用邮箱链接登录",
    linkHeading: "继续登录",
    linkSubcopy: "点击下方按钮完成登录。链接只能使用一次。",
    continue: "继续登录",
    linkInvalid: "登录链接无效或已过期。",
    backToLogin: "返回登录",
    login: "登录",
    signUp: "创建账号",
    working: "处理中…",
    switchToRegister: "还没有账号？创建一个",
    switchToLogin: "已有账号？去登录",
    errors: {
      displayName: "请输入显示名称。",
      email: "请输入邮箱。",
      account: "请输入账号或邮箱。",
      password: "请输入密码。",
    },
  },
  errors: {
    notConfigured: "尚未配置 PUBLIC_API_URL。请复制 .env.example 为 .env。",
    network: (origin) =>
      `无法连接 agora-api。请确认服务已启动，并将 ${origin} 加入 ALLOWED_ORIGINS（修改后需重启服务）。`,
    authStatus: (status) => {
      switch (status) {
        case 400:
          return "账号信息不符合要求，请检查后重试。";
        case 401:
          return "账号或密码不正确。";
        case 403:
          return "该账号需要先修改密码。";
        case 409:
          return "该账号已注册。";
        case 429:
          return "尝试次数过多，请稍后再试。";
        case 502:
          return "认证服务返回了异常响应。";
        case 503:
          return "认证服务暂不可用，请稍后再试。";
        default:
          return "请求失败，请稍后再试。";
      }
    },
    requestFailed: (status) => {
      switch (status) {
        case 400:
          return "提交内容不符合要求，请检查后重试。";
        case 401:
          return "登录状态已失效，请重新登录。";
        case 403:
          return "没有权限执行该操作。";
        case 409:
          return "该机构或岗位已存在。";
        case 413:
          return "文件不能超过 8 MB。";
        case 502:
        case 503:
          return "文件服务暂不可用，请稍后再试。";
        default:
          return `请求失败（HTTP ${status}）。`;
      }
    },
    statusHttp: (status) => `接口状态请求失败（HTTP ${status}）。`,
    statusPayload: "接口状态返回了异常数据。",
    unknown: "无法连接服务器，请重试。",
  },
};

export const en: Messages = {
  meta: {
    title: "Qimu",
    description: "A mobile workspace built with Astro and Svelte, wired to a Go API.",
  },
  brand: {
    name: "Qimu",
    mark: "Q",
    home: "Qimu home",
  },
  language: {
    toggleText: "中",
    toggleLabel: "Switch to Chinese",
  },
  common: {
    signOut: "Sign out",
    checkingSession: "Checking session…",
    yourAccount: "your account",
  },
  identity: {
    label: "Sign-in role",
    jobSeeker: "Job seeker",
    recruiter: "Recruiter",
  },
  nav: {
    label: "Primary navigation",
    home: "Home",
    search: "Search",
    messages: "Messages",
    profile: "Profile",
  },
  search: {
    heading: "Search roles",
    copy: "Find open roles by keyword and city.",
    keyword: "Keyword",
    keywordPlaceholder: "Role title, organisation",
    city: "City",
    cityPlaceholder: "e.g. Shanghai",
    submit: "Search",
    working: "Searching…",
    idle: "Enter a keyword or city to start searching.",
    results: "Results",
    empty: "No open roles match that search.",
    failed: "Search failed.",
    truncated: (count) =>
      `Showing the first ${count} results. Narrow the search to see more.`,
  },
  messages: {
    heading: "Messages",
    copy: "Review results and role updates will appear here.",
    empty: "No messages yet.",
    hint: "In-app messaging is not connected yet.",
  },
  profile: {
    heading: "Profile",
    name: "Display name",
    identifier: "Account",
    subject: "Subject ID",
    identity: "Sign-in role",
    identityHint: "The role is chosen at sign-in. Sign out to switch.",
  },
  positions: {
    toggle: "Roles",
    collapse: "Hide roles",
    loading: "Loading roles…",
    failed: "Could not load roles.",
    empty: "No roles yet. Post one to get started.",
    create: "Post a role",
    createHeading: "Post a role",
    createHint:
      "A new role is saved as a draft. Publishing to “open” unlocks once the organisation is verified.",
    createSubmit: "Save draft",
    createSubmitting: "Saving…",
    cancel: "Cancel",
    created: "Role saved as a draft.",
    notVerified: "The organisation is not verified yet, so publishing is locked.",
    publish: "Publish",
    pause: "Pause",
    resume: "Reopen",
    close: "Close",
    actionFailed: "The change failed. Try again later.",
    headcountValue: (count) => `${count} ${count === 1 ? "hire" : "hires"}`,
    fields: {
      title: "Role title",
      titlePlaceholder: "e.g. Nurse / Frontend engineer",
      employmentType: "Employment type",
      workMode: "Work mode",
      headcount: "Headcount",
      salary: "Salary",
      negotiable: "Negotiable",
      salaryMin: "Minimum",
      salaryMax: "Maximum",
      salaryUnit: "Unit",
      education: "Education",
      experience: "Experience",
      city: "City",
      cityPlaceholder: "e.g. Shanghai",
      description: "Responsibilities",
      descriptionPlaceholder: "What the role does day to day",
      requirements: "Requirements",
      requirementsPlaceholder: "Education, licences, skills",
    },
    errors: {
      title: "Enter the role title.",
      headcount: "Headcount must be at least 1.",
      salaryMin: "Enter the minimum salary.",
      salaryRange: "Maximum salary cannot be below the minimum.",
    },
  },
  workspace: {
    seekerHeading: "Job seeker workspace",
    seekerCopy: "Every role below is still open and not past its deadline.",
    recruiterHeading: "Recruiter workspace",
    recruiterCopy:
      "Maintain organisation profiles and postings wherever you are a member.",
    organizations: (count) =>
      `${count} ${count === 1 ? "organisation" : "organisations"}`,
    loading: "Loading your organisations…",
    failed: "Could not load your organisations.",
    empty: "You do not belong to an organisation yet.",
    emptyHint:
      "Create an organisation and pass platform review before posting roles.",
    positionsCount: (count) =>
      `${count} open ${count === 1 ? "role" : "roles"}`,
    positionsLoading: "Loading open roles…",
    positionsFailed: "Could not load open roles.",
    positionsEmpty: "No open roles right now.",
    loadMore: "Load more",
    loadingMore: "Loading…",
    salaryNegotiable: "Negotiable",
    salaryRange: (min, max, unit) => {
      const per: Record<string, string> = {
        "时": "hour",
        "日": "day",
        "月": "month",
        "年": "year",
      };
      const suffix = per[unit] ? `CNY/${per[unit]}` : "CNY";
      if (min !== null && max !== null) return `${min}–${max} ${suffix}`;
      if (min !== null) return `${min}+ ${suffix}`;
      if (max !== null) return `up to ${max} ${suffix}`;
      return "";
    },
    createAction: "Create organisation",
    createHeading: "Create organisation",
    createHint:
      "You become the owner. Posting roles unlocks once the platform reviews and verifies the organisation.",
    createSubmit: "Submit for review",
    createSubmitting: "Submitting…",
    createCancel: "Cancel",
    created: "Submitted. Waiting for platform review.",
    pendingHint: "Posting roles unlocks after the organisation is verified.",
    fields: {
      name: "Organisation name",
      namePlaceholder: "Full legal name on the licence",
      orgType: "Organisation type",
      shortName: "Short name",
      shortNamePlaceholder: "Optional",
      creditCode: "Unified social credit code",
      creditCodePlaceholder: "18-character code",
      license: "Business licence",
      licenseHelper: "JPG, PNG, WebP or PDF, up to 8 MB.",
    },
    createErrors: {
      name: "Enter the organisation name.",
      creditCode: "Enter the unified social credit code.",
      license: "Attach the business licence.",
      fileSize: "The licence must be 8 MB or smaller.",
    },
  },
  hero: {
    line1: "A calm place",
    line2Prefix: "to\u00a0",
    accent: "assemble",
    line2Suffix: ".",
    signedIn: (name) => `Signed in as ${name}. Mobile workspace.`,
  },
  status: {
    heading: "API status",
    phase: {
      checking: "checking",
      online: "online",
      offline: "offline",
    },
    checkedAt: (time) => `Checked ${time}`,
    http: "HTTP",
    database: "Database",
    latency: "Latency",
    ready: "ready",
    notReady: "not ready",
    endpoint: "Endpoint",
    notConfigured: "Not configured",
    reported: (status) => `API reported status "${status}".`,
    openEndpoint: "Open status endpoint",
    probe: "Probe API",
    probing: "Probing…",
    latencyValue: (milliseconds) => `${milliseconds} ms`,
  },
  stack: {
    heading: "Stack",
    shell: "Shell",
    surface: "Surface",
    backend: "Backend",
    shellValue: "Astro static",
    surfaceValue: "Svelte client",
    backendValue: "Go / PostgreSQL",
  },
  access: {
    heading: "Access",
    roles: (count) => `${count} ${count === 1 ? "role" : "roles"}`,
    empty: "No base-data roles assigned yet.",
    unavailable: "Base data is unavailable; sign-in still works.",
  },
  footer: {
    tagline: "Qimu / client-side rendered",
    stack: "Tailwind 4 · Astro · Svelte 5",
  },
  auth: {
    eyebrow: "Qimu account",
    loginHeading: "Login to continue.",
    registerHeading: "Create your account.",
    loginSubcopyJobSeeker: "Sign in to browse open roles and apply.",
    loginSubcopyRecruiter: "Sign in to run your organisation and postings.",
    registerSubcopyJobSeeker: "One account to start job hunting.",
    registerSubcopyRecruiter: "One account to start posting roles.",
    displayName: "Display name",
    displayNamePlaceholder: "How we should address you",
    email: "Email",
    emailPlaceholder: "you@example.com",
    accountOrEmail: "Account or email",
    identifierPlaceholder: "name@example.com",
    password: "Password",
    sendLink: "Send sign-in link",
    noticeLinkSent: "If that email is registered, a sign-in link is on its way. Check your inbox.",
    usePasswordLogin: "Use password instead",
    useEmailLinkLogin: "Use email link instead",
    linkHeading: "Continue signing in",
    linkSubcopy: "Click the button below to finish signing in. The link works once.",
    continue: "Continue",
    linkInvalid: "This sign-in link is invalid or has expired.",
    backToLogin: "Back to sign in",
    login: "Login",
    signUp: "Sign up",
    working: "Working…",
    switchToRegister: "New here? Sign up",
    switchToLogin: "Already registered? Login",
    errors: {
      displayName: "Enter a display name.",
      email: "Enter your email.",
      account: "Enter your account or email.",
      password: "Enter your password.",
    },
  },
  errors: {
    notConfigured: "PUBLIC_API_URL is not configured. Copy .env.example to .env.",
    network: (origin) =>
      `Could not reach agora-api. Confirm it is running and add ${origin} to ALLOWED_ORIGINS (restart after changing it).`,
    authStatus: (status) => {
      switch (status) {
        case 400:
          return "Check the account details and try again.";
        case 401:
          return "Incorrect account or password.";
        case 403:
          return "This account must change its password first.";
        case 409:
          return "That account is already registered.";
        case 429:
          return "Too many attempts. Try again later.";
        case 502:
          return "The authentication service returned an unexpected response.";
        case 503:
          return "Authentication is temporarily unavailable. Try again later.";
        default:
          return "Request failed. Try again later.";
      }
    },
    statusHttp: (status) => `Status request failed with HTTP ${status}.`,
    requestFailed: (status) => {
      switch (status) {
        case 400:
          return "The request was rejected. Check the form and try again.";
        case 401:
          return "Your session expired. Sign in again.";
        case 403:
          return "You do not have permission to do that.";
        case 409:
          return "That record already exists.";
        case 413:
          return "The file must be 8 MB or smaller.";
        case 502:
        case 503:
          return "The file service is unavailable. Try again later.";
        default:
          return `Request failed with HTTP ${status}.`;
      }
    },
    statusPayload: "The status endpoint returned an unexpected payload.",
    unknown: "Could not reach the server. Try again.",
  },
};
