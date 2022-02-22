package io.soffa.foundation.service.data;

import com.zaxxer.hikari.HikariDataSource;
import io.soffa.foundation.commons.CollectionUtil;
import io.soffa.foundation.commons.EventBus;
import io.soffa.foundation.commons.Logger;
import io.soffa.foundation.commons.TextUtil;
import io.soffa.foundation.core.context.RequestContextHolder;
import io.soffa.foundation.core.db.*;
import io.soffa.foundation.core.events.DatabaseReadyEvent;
import io.soffa.foundation.core.models.TenantId;
import io.soffa.foundation.errors.InvalidTenantException;
import io.soffa.foundation.errors.NotImplementedException;
import io.soffa.foundation.errors.TechnicalException;
import lombok.SneakyThrows;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class DBImpl extends AbstractDataSource implements ApplicationListener<ContextRefreshedEvent>, DB {

    // public static final String NONE = "none";
    private static final Logger LOG = Logger.get(DBImpl.class);
    private final Map<Object, Object> dataSources = new ConcurrentHashMap<>();
    private String tablesPrefix;
    private final String appicationName;
    private String tenanstListQuery;
    private LockProvider lockProvider;
    private static final String TENANT_PLACEHOLDER = "__tenant__";
    private static final String DEFAULT_DS = "default";
    private final Map<String, Boolean> migrated = new ConcurrentHashMap<>();
    private final Map<Object, DataSourceConfig> dsConfigs = new ConcurrentHashMap<>();
    private final ApplicationContext context;
    // private final PubSubClient binaryClient;
    private static final AtomicReference<String> LOCK = new AtomicReference<>("DB_LOCK");

    @SneakyThrows
    public DBImpl(final ApplicationContext context,
                  final DbConfig dbConfig,
                  final String appicationName) {

        super();

        this.context = context;
        this.appicationName = appicationName;
        if (dbConfig != null) {
            this.tenanstListQuery = dbConfig.getTenantListQuery();
            this.tablesPrefix = dbConfig.getTablesPrefix();

            // setLenientFallback(false);
            createDatasources(dbConfig.getDatasources());
            // TenantHolder.hasDefault = dataSources.containsKey(TenantId.DEFAULT_VALUE);
            // super.setTargetDataSources(ImmutableMap.copyOf(dataSources));
            createLockTable();
            configure();
        }
    }

    @Override
    public String getTablesPrefix() {
        return tablesPrefix;
    }

    private void createDatasources(Map<String, DataSourceConfig> datasources) {
        if (datasources == null || datasources.isEmpty()) {
            LOG.warn("No datasources configured for this service.");
        } else {
            for (Map.Entry<String, DataSourceConfig> dbLink : datasources.entrySet()) {
                registerDatasource(dbLink.getKey(), dbLink.getValue(), false);
            }
            if (!dataSources.containsKey(DEFAULT_DS)) {
                throw new TechnicalException("No default datasource provided");
            }
        }
    }

    private void registerDatasource(String id, DataSourceConfig link, boolean migrate) {
        if (dataSources.containsKey(id)) {
            LOG.warn("Datasource with id {} is already registered", id);
            return;
        }
        String url = link.getUrl().replace(TENANT_PLACEHOLDER, id).replace(TENANT_PLACEHOLDER.toUpperCase(), id);
        if (!TENANT_PLACEHOLDER.equalsIgnoreCase(id)) {
            DataSource ds = DbHelper.createDataSource(DataSourceProperties.create(appicationName, id, url), link);
            dsConfigs.put(ds, link);
            if (migrate) {
                applyMigrations(ds);
            }
            dataSources.put(id.toLowerCase(), ds);
        }
        dsConfigs.put(id.toLowerCase(), link);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return determineTargetDataSource().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) {
        throw new NotImplementedException("Not supported");
    }

    @NotNull
    @Override
    public DataSource determineTargetDataSource() {
        Object lookupKey = determineCurrentLookupKey();
        if (lookupKey != null) {
            lookupKey = lookupKey.toString().toLowerCase();
        }
        if (!dataSources.containsKey(lookupKey)) {
            throw new InvalidTenantException("%s is not a valid database link", lookupKey);
        }
        return (DataSource) dataSources.get(lookupKey);
    }

    private Object determineCurrentLookupKey() {
        String linkId = RequestContextHolder.getTenant().orElse(null);
        if (linkId == null) {
            if (dataSources.containsKey(TenantId.DEFAULT_VALUE)) {
                return TenantId.DEFAULT_VALUE;
            }
            throw new InvalidTenantException("Missing database link. Don't forget to set active tenant with TenantHolder.set()");
        }
        linkId = linkId.toLowerCase();
        if (!dataSources.containsKey(linkId) && dsConfigs.containsKey(TENANT_PLACEHOLDER)) {
            throw new InvalidTenantException("No datasource registered for tenant %s", linkId);
        }
        return linkId;
    }

    public DataSource getDefault() {
        boolean hasOneItem = dataSources.size() == 1;
        if (hasOneItem) {
            return (DataSource) dataSources.values().iterator().next();
        }
        if (dataSources.containsKey(TenantId.DEFAULT_VALUE)) {
            return (DataSource) dataSources.get(TenantId.DEFAULT_VALUE);
        }
        throw new TechnicalException("No default datasource registered");
    }

    @Override
    public void onApplicationEvent(@NonNull ContextRefreshedEvent event) {
        EventBus.post(new DatabaseReadyEvent());
        new Thread(this::configureTenants).start();
    }

    @Override
    public void createSchema(String tenantId, String schema) {
        DataSource ds = get(tenantId);
        if (ds == null) {
            throw new TechnicalException("Datasource not registered: " + tenantId);
        }
        Jdbi.create(ds).useHandle(handle -> {
            if (handle.execute("CREATE SCHEMA IF NOT EXISTS " + schema) > 0) {
                LOG.info("New schema created: %s", schema);
            }
        });
    }

    public void applyMigrations() {
        for (Map.Entry<Object, Object> entry : dataSources.entrySet()) {
            applyMigrations((DataSource) entry.getValue());
        }
    }

    private void applyMigrations(DataSource dataSource) {
        /*if (dataSource instanceof MockDataSource) {
            return;
        }*/
        if (dataSource instanceof HikariDataSource) {
            applyMigrations((HikariDataSource) dataSource);
        } else {
            throw new TechnicalException("Non HikariDatasource migrations  not supported");
        }
    }

    private String findChangeLogPath(DataSourceConfig link) {
        String changelogPath = null;
        boolean hasMigration = !("false".equals(link.getMigration()) || "no".equals(link.getMigration()));
        if (hasMigration) {
            if (TextUtil.isNotEmpty(link.getMigration()) && !"true".equals(link.getMigration())) {
                changelogPath = "/db/changelog/" + link.getMigration() + ".xml";
            } else {
                changelogPath = "/db/changelog/" + appicationName + ".xml";
            }
            if (TextUtil.isNotEmpty(changelogPath)) {
                ResourceLoader resourceLoader = new DefaultResourceLoader();
                if (!resourceLoader.getResource(changelogPath).exists()) {
                    throw new TechnicalException("Changelog file not found: " + changelogPath);
                }
            }
        }
        return changelogPath;
    }

    private void applyMigrations(HikariDataSource dataSource) {
        DataSourceConfig link = dsConfigs.get(dataSource);
        String linkId = link.getName().toLowerCase();
        if (migrated.containsKey(link.getName().toLowerCase())) {
            return;
        }
        synchronized (LOCK) {
            withLock("db-migration-" + linkId, 60, 30, () -> {
                String changelogPath = findChangeLogPath(link);
                if (TextUtil.isNotEmpty(changelogPath)) {
                    DbHelper.applyMigrations(dataSource, changelogPath, tablesPrefix, appicationName);
                }
                migrated.put(link.getName().toLowerCase(), true);
                LOG.info("Migrations applied for %s", link.getName());
            });
        }
    }

    public DataSource get(String tenant) {
        return Objects.requireNonNull((DataSource) dataSources.get(tenant));
    }

    @Override
    public void applyMigrations(String tenantId) {
        registerDatasource(tenantId, dsConfigs.get(TENANT_PLACEHOLDER), true);
    }

    @Override
    public boolean tenantExists(String tenant) {
        return dataSources.containsKey(tenant.toLowerCase());
    }

    @Override
    public List<Map<String, Object>> query(String datasource, String query) {
        DataSource ds = (DataSource) dataSources.get(datasource.toLowerCase());
        return Jdbi.create(ds).withHandle(handle -> handle.createQuery(query).setMaxRows(1000).mapToMap().list());
    }

    @Override
    public List<Map<String, Object>> query(String query) {
        DataSource ds = determineTargetDataSource();
        return Jdbi.create(ds).withHandle(handle -> handle.createQuery(query).setMaxRows(1000).mapToMap().list());
    }

    @SneakyThrows
    private void createLockTable() {
        DataSource defaultDs = (DataSource) dataSources.get(DEFAULT_DS);
        LockProvider lockProvider = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(defaultDs))
            .withTableName(tablesPrefix + "f_shedlock")
            .usingDbTime()
            .build());
        try {
            Jdbi.create(defaultDs).useTransaction(handle -> {
                handle.execute("CREATE TABLE IF NOT EXISTS " + tablesPrefix + "f_shedlock(name VARCHAR(64) NOT NULL, lock_until TIMESTAMP NOT NULL, locked_at TIMESTAMP NOT NULL, locked_by VARCHAR(255) NOT NULL, PRIMARY KEY (name))");
            });
        } catch (Exception e) {
            // Will ignore because the table might have been created by another instance of the service
            LOG.warn(e.getMessage(), e);
        }
        this.lockProvider = lockProvider;
    }


    @Override
    public void withLock(String name, Duration atMost, Duration atLeast, Runnable runnable) {
        LockConfiguration config = new LockConfiguration(Instant.now(), name, atMost, atLeast);
        lockProvider.lock(config).ifPresent(simpleLock -> {
            try {
                runnable.run();
            } finally {
                simpleLock.unlock();
            }
        });
    }


    public void configure() {
        dataSources.forEach((key, value) -> {
            final DataSource datasource = (DataSource) value;
            applyMigrations(datasource);
        });
    }

    public void configureTenants() {
        DataSource defaultDs = (DataSource) dataSources.get(DEFAULT_DS);

        if (!dsConfigs.containsKey(TENANT_PLACEHOLDER)) {
            LOG.debug("No TenantDS provided, skipping tenants migration.");
        }

        final Set<String> tenants = new HashSet<>();
        if (TextUtil.isNotEmpty(tenanstListQuery)) {
            LOG.info("Loading tenants from database");
            Jdbi jdbi = Jdbi.create(defaultDs);
            jdbi.useHandle(handle -> {
                LOG.info("Loading tenants from query: %s", tenanstListQuery);
                List<String> results = handle.createQuery(tenanstListQuery).mapTo(String.class).collect(Collectors.toList());
                if (CollectionUtil.isNotEmpty(results)) {
                    tenants.addAll(results);
                }
            });
        } else {
            LOG.info("Loading tenants with TenantsLoader");
            try {
                TenantsLoader tenantsLoader = context.getBean(TenantsLoader.class);
                tenants.addAll(tenantsLoader.getTenantList());
            } catch (NoSuchBeanDefinitionException e) {
                LOG.error("No TenantsLoader defined");
            } catch (Exception e) {
                LOG.error("Error loading tenants", e);
            }
        }

        LOG.info("Tenants loaded: %d", tenants.size());
        for (String tenant : tenants) {
            registerDatasource(tenant, dsConfigs.get(TENANT_PLACEHOLDER), true);
        }
        LOG.info("Database is now configured");
    }

}