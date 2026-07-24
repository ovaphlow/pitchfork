service-idp-go 是鉴权项目 service-nexus-shared是通用工程 包括settings、messages等功能 service-vertx-kotlin是业务工程 包含若干个app ui-astro是前端工程 包含若
  干个app。现在我要开始开发一个养老系统，可以用aceso这个app 已经有一部分代码了。首先检查 service-vertx-kotlin 将其中的settings messages files表结构在service-
  nexus-shared项目中记录到文档 然后删除这几个公共lib 以后都用service-nexus-shared
