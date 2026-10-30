package com.ctds.connector.sdk.adapter.database;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 测试用 JDBC 驱动（{@code DriverManager} 注册；零真实数据库）：可配置连接失败、表计数，
 * 并记录"是否设置只读 / 是否出现写语句"，用于只读语义的可证伪断言。
 */
final class FakeJdbcDriver implements Driver {

    private static final String URL_PREFIX = "jdbc:mysql://";

    private static SQLException connectFailure;
    private static long tableCount = 7L;
    private static boolean readOnlyRequested;
    private static boolean writeAttempted;
    private static String lastSchemaParameter;

    private final Connection connection = connection();

    static void reset() {
        connectFailure = null;
        tableCount = 7L;
        readOnlyRequested = false;
        writeAttempted = false;
        lastSchemaParameter = null;
    }

    static void failOnConnect(final SQLException failure) {
        connectFailure = failure;
    }

    static void tableCount(final long count) {
        tableCount = count;
    }

    static boolean readOnlyRequested() {
        return readOnlyRequested;
    }

    static boolean writeAttempted() {
        return writeAttempted;
    }

    static String lastSchemaParameter() {
        return lastSchemaParameter;
    }

    @Override
    public Connection connect(final String url, final Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        if (connectFailure != null) {
            throw connectFailure;
        }
        return connection;
    }

    @Override
    public boolean acceptsURL(final String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(final String url, final Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 8;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getGlobal();
    }

    private static Connection connection() {
        return (Connection) proxy(Connection.class, (self, method, args) -> {
            switch (method.getName()) {
                case "setReadOnly":
                    readOnlyRequested = Boolean.TRUE.equals(args[0]);
                    return null;
                case "setAutoCommit":
                case "close":
                    return null;
                case "prepareStatement":
                    return preparedStatement((String) args[0]);
                case "createStatement":
                    return statement();
                case "isClosed":
                    return false;
                case "toString":
                    return "FakeConnection";
                case "hashCode":
                    return System.identityHashCode(self);
                case "equals":
                    return self == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    private static PreparedStatement preparedStatement(final String sql) {
        return (PreparedStatement) proxy(PreparedStatement.class, (self, method, args) -> {
            switch (method.getName()) {
                case "setString":
                    lastSchemaParameter = (String) args[1];
                    return null;
                case "executeQuery":
                    return resultSet(sql);
                case "execute":
                case "executeUpdate":
                    writeAttempted = true;
                    throw new SQLException("只读用例不允许写语句", "42000");
                case "close":
                    return null;
                case "toString":
                    return "FakePreparedStatement";
                case "hashCode":
                    return System.identityHashCode(self);
                case "equals":
                    return self == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    private static Statement statement() {
        return (Statement) proxy(Statement.class, (self, method, args) -> {
            switch (method.getName()) {
                case "executeQuery":
                    return resultSet((String) args[0]);
                case "execute":
                case "executeUpdate":
                    writeAttempted = true;
                    throw new SQLException("只读用例不允许写语句", "42000");
                case "close":
                    return null;
                case "toString":
                    return "FakeStatement";
                case "hashCode":
                    return System.identityHashCode(self);
                case "equals":
                    return self == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    private static ResultSet resultSet(final String sql) {
        final boolean selectOne = sql != null && sql.trim().toUpperCase().startsWith("SELECT 1");
        return (ResultSet) proxy(ResultSet.class, new InvocationHandler() {
            private boolean consumed;

            @Override
            public Object invoke(final Object self, final Method method, final Object[] args)
                    throws Throwable {
                switch (method.getName()) {
                    case "next":
                        if (consumed) {
                            return false;
                        }
                        consumed = true;
                        return true;
                    case "getLong":
                        return selectOne ? 1L : tableCount;
                    case "getInt":
                        return selectOne ? 1 : (int) tableCount;
                    case "close":
                        return null;
                    case "toString":
                        return "FakeResultSet";
                    case "hashCode":
                        return System.identityHashCode(self);
                    case "equals":
                        return self == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            }
        });
    }

    private static Object proxy(final Class<?> type, final InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return 0;
    }
}
