# JDBC Drivers

可选驱动包目录，按如下结构放置：

- `drivers/mysql/8.x/driver.jar`
- `drivers/postgresql/universal/driver.jar`
- `drivers/sqlserver/universal/driver.jar`
- `drivers/oracle/universal/driver.jar`
- `drivers/sqlite/universal/driver.jar`

未放置驱动包时，系统会回退到应用 classpath 中已有的 JDBC 驱动。
