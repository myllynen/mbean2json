/*
 * MBean2JSON
 *
 * Copyright (C) 2016-2018 Marko Myllynen <myllynen@redhat.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 * References:
 * https://docs.oracle.com/javase/8/docs/technotes/guides/jmx/index.html
 * http://www.oracle.com/us/technologies/java/best-practices-jsp-136021.html
 * https://docs.oracle.com/javase/8/docs/api/javax/management/ObjectName.html
 * https://docs.oracle.com/javase/8/docs/api/java/lang/management/ManagementFactory.html
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MalformedObjectNameException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

public class MBean2JSON {
    // Logging
    private static final Logger logger = Logger.getLogger(MBean2JSON.class.getName());
    private static final String DEFAULT_LOG_FORMAT = "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$-6s: %3$s %5$s%6$s%n";

    // Defaults
    private static final String DEFAULT_CONFIG       = "mbean2json.properties";
    private static final String DEFAULT_JVM_TARGETS  = "localhost:9875";
    private static final String DEFAULT_MBEAN_FILTER = "java.lang:*!java.nio:*";
    private static final boolean DEFAULT_COMPAT_ONLY = false;

    // Command line options
    private static final String OPT_CONFIG       = "--config";
    private static final String OPT_JVM_TARGETS  = "--jvm-targets";
    private static final String OPT_MBEAN_FILTER = "--mbean-filter";
    private static final String OPT_COMPAT_ONLY  = "--compat-only";

    // Options
    private static String configFile  = DEFAULT_CONFIG;
    private static String jvmTargets  = DEFAULT_JVM_TARGETS;
    private static String mbeanFilter = DEFAULT_MBEAN_FILTER;
    private static boolean compatOnly = DEFAULT_COMPAT_ONLY;

    // Authentication using environment variable (optional)
    private static final String JMX_CONNECTOR_CREDENTIALS = "JMX_CONNECTOR_CREDENTIALS";

    // Helpers
    private static boolean configOpt = false;
    private static boolean firstItem = true;

    // Setup logging
    private static void setupLogging() {
        if (System.getProperty("java.util.logging.SimpleFormatter.format") == null) {
            System.setProperty("java.util.logging.SimpleFormatter.format", DEFAULT_LOG_FORMAT);
        }
        if (System.getProperty(MBean2JSON.class.getName() + ".level") != null) {
            String level = System.getProperty(MBean2JSON.class.getName() + ".level");
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.parse(level));
            Handler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.parse(level));
            logger.addHandler(consoleHandler);
            logger.config("Log level set to: " + level);
        }
    }

    // Rudimentary command line parser with no dependencies to non-standard classes
    private static void parseCommandLine(final String[] args) throws IllegalArgumentException {
        List<String> argsList = Arrays.asList(args);
        for (Iterator<String> iter = argsList.iterator(); iter.hasNext(); ) {
            String arg = iter.next();
            try {
                if (arg.equals(OPT_CONFIG)) {
                    configFile = iter.next();
                    configOpt = true;
                } else if (arg.equals(OPT_JVM_TARGETS)) {
                    jvmTargets = iter.next();
                } else if (arg.equals(OPT_MBEAN_FILTER)) {
                    mbeanFilter = iter.next();
                } else if (arg.equals(OPT_COMPAT_ONLY)) {
                    compatOnly = true;
                } else {
                    throw new IllegalArgumentException("Unrecognized option '" + arg + "'");
                }
            } catch (NoSuchElementException e) {
                throw new IllegalArgumentException("Option requires an argument");
            }
        }

        if (mbeanFilter.equals("*")) {
            mbeanFilter = "";
        }
    }

    // Basic configuration file reader with no dependencies to non-standard classes
    private static void parseConfigurationFile(final String configFile) throws FileNotFoundException, IOException {
        if (!configOpt && !new File(configFile).isFile()) {
            // Only user specified config must be found
            return;
        }

        Properties prop = new Properties();
        InputStream is = new FileInputStream(configFile);
        prop.load(is);

        jvmTargets = prop.getProperty("mbean2json.jvmtargets", DEFAULT_JVM_TARGETS);

        mbeanFilter = prop.getProperty("mbean2json.mbeanfilter", DEFAULT_MBEAN_FILTER);
        if (mbeanFilter.equals("*")) {
            mbeanFilter = "";
        }
    }

    // Prepare JVM target URLs
    private static Set<String> prepareJVMURLs() throws IllegalArgumentException {
        // Construct full JMX Service URLs
        Set<String> jvms = new HashSet<String>();
        if (jvmTargets != null) {
            for (String target: jvmTargets.split(",")) {
                try {
                    if (!target.contains("/")) {
                        String host = target.split(":")[0];
                        String port = target.split(":")[1];
                        target = "service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/jmxrmi";
                    }
                    jvms.add(target);
                } catch (Exception ArrayIndexOutOfBoundsException) {
                    throw new IllegalArgumentException("Missing port in JVM target '" + target + "'");
                }
            }
        }
        if (jvmTargets != null) {
            logger.config("Using JVM targets: " + jvms);
        } else {
            logger.warning("No JVM targets specified!");
        }
        return jvms;
    }

    // Get MBean filters
    private static Set<String> getMBeanFilters() {
        Set<String> filters = new HashSet<String>();
        for (String str: Arrays.asList(mbeanFilter.split("\\!"))) {
            filters.add(str);
        }
        logger.config("Using attribute filter: " + filters);
        return filters;
    }

    // Helper to construct a sanitized metric name
    private static String constructSaneName(final String mbeanName, final String attrName) {
        String base = mbeanName.replaceAll(":type=", ".").replaceAll(" ", "_");
        base = base.replaceAll(",name=Compressed_Class_Space", ".ccs.");
        base = base.replaceAll(",name=Code_Cache", ".cc.");
        base = base.replaceAll(".MBeanServerDelegate", "");
        base = base.replaceAll(",name=", ".");
        if (!base.endsWith(".")) {
            base = base + ".";
        }
        base = base.replaceAll(".lang", "").replaceAll(".nio", "");
        String name = attrName.replaceAll(" ", "_").replaceAll("CollectionUsage", "CU_");
        return base.toLowerCase() + name.toLowerCase();
    }

    // Helper to construct a metric description
    private static String constructDescription(final String mbeanName, final String attrName) {
        return mbeanName + " / " + attrName;
    }

    // Helper to print individual metric data
    private static void printMetricData(ObjectName mbean, final MBeanAttributeInfo attr, final String item) {
        String name = constructSaneName(mbean.toString(), attr.getName());
        String desc = constructDescription(mbean.toString(), attr.getName());

        if (item != null) {
            name = name + "[" + item + "]";
        }

        if (!firstItem) {
            System.out.print(",\n");
        }
        firstItem = false;

        System.out.print("        {\n");
        System.out.print("            \"name\": \"" + name + "\",\n");
        System.out.print("            \"description\": \"" + desc + "\",\n");
        //System.out.print("            \"optional\": true,\n");
        //System.out.print("            \"semantics\": \"" + semantics + "\",\n");
        //System.out.print("            \"units\": \"" + units + "\",\n");
        System.out.print("            \"mBeanName\": \"" + mbean.toString() + "\",\n");
        System.out.print("            \"mBeanAttributeName\": \"" + attr.getName() + "\"");
        if (item == null) {
            System.out.print("\n");
        } else {
            System.out.print(",\n            \"mBeanCompositeDataItem\": \"" + item + "\"\n");
        }
        System.out.print("        }");
    }

    // Extract items from CompositeData
    private static void extractCompositeDataItems(final CompositeData cds, Set<String> dataItems) {
        CompositeType comp = cds.getCompositeType();
        for (String key: comp.keySet()) {
            Object value = cds.get(key);
            if (value instanceof TabularData) {
                return;
            } else if (value instanceof CompositeData) {
                extractCompositeDataItems(CompositeData.class.cast(value), dataItems);
            } else if (value instanceof SimpleType) {
                dataItems.add(null);
            } else {
                dataItems.add(key);
            }
        }
    }

    // Fetch and print data from the server using current filters
    private static void fetchAndPrintItems(final MBeanServerConnection connection, final Set<String> filters) throws IOException {
        for (String filter: filters) {
            try {
                logger.finer("Querying MBeans with filter '" + filter + "'.");

                ObjectName query = new ObjectName(filter);

                Set<ObjectName> mbeanNames = connection.queryNames(query, null);

                if (mbeanNames.size() == 0) {
                    logger.config("No MBean matches filter '" + filter + "'!");
                    continue;
               	}

                // Iterate all matched MBeans
                for (ObjectName mbean: mbeanNames) {
                    try {
                        logger.fine("Processing MBean: " + mbean.toString());

                        for (MBeanAttributeInfo attr: connection.getMBeanInfo(mbean).getAttributes()) {

                            if (!attr.isReadable()) {
                                logger.finer("Skipping unreadable attribute: " + attr.getName());
                                continue;
                            }

                            logger.finer("Processing attribute: " + attr.getName());

                            if (compatOnly &&
                                (attr.getName().equals("BootClassPath") ||
                                 (mbean.toString().contains("MarkSweep") && attr.getName().equals("LastGcInfo")) ||
                                 (mbean.toString().contains("Scavenge") && attr.getName().equals("LastGcInfo")))) {
                                logger.finer("Ignoring " + attr.getName() + " due to unsupported attr type: " + attr.getType());
                                continue;
                            }

                            Object obj = null;
                            try {
                                // getAttributes() is a few milliseconds faster against JVM
                                // but slower with WildFly and almost chokes with Cassandra
                                obj = connection.getAttribute(mbean, attr.getName());
                                if (obj == null) {
                                    // Attribute not available, ignore
                                    logger.finer("Received null value for attribute: " + attr.getName());
                                    continue;
                                }
                            } catch (AttributeNotFoundException e) {
                                // Attribute not available, ignore
                                logger.finer(e.toString());
                                continue;
                            } catch (MBeanException e) {
                                // Server side issue, ignore
                                logger.finer(e.toString());
                                continue;
                            } catch (IOException e) {
                                // Server side issue, ignore
                                logger.finer(e.toString());
                                continue;
                            } catch (NumberFormatException e) {
                                // Server side issue, ignore
                                logger.finer(e.toString());
                                continue;
                            } catch (RuntimeMBeanException e) {
                                // Server side issue, ignore
                                logger.finer(e.toString());
                                continue;
                            }

                            // Print the results
                            if (obj instanceof ObjectName) {
                                continue;
                            } else if (obj instanceof TabularData) {
                                continue;
                            } else if (obj instanceof CompositeData) {
                                Set<String> dataItems = new HashSet<String>();
                                CompositeData cds = CompositeData.class.cast(obj);
                                extractCompositeDataItems(cds, dataItems);
                                for (String item: dataItems) {
                                    printMetricData(mbean, attr, item);
                                }
                            } else {
                                if (compatOnly && obj.getClass().isArray()) {
                                    continue;
                                }
                                printMetricData(mbean, attr, null);
                            }
                        }
                    } catch (IntrospectionException e) {
                        logger.finer(e.toString());
                    } catch (ReflectionException e) {
                        logger.finer(e.toString());
                    }
                }
            } catch (InstanceNotFoundException e) {
                logger.finer(e.toString());
            } catch (MalformedObjectNameException e) {
                logger.severe("Malformed MBean filter: " + mbeanFilter);
                System.exit(1);
            }
        }
    }

    // Worker to retrieve data from a JVM
    private static final class JVMWorker implements Runnable {
        private final String strUrl;
        private final Set<String> filters;
        private static Map<String, String[]> env;

        // Optionally enable JMX authentication
        private static void setupAuthentication() {
            if (env == null) {
                env = new HashMap<String, String[]>();
                String value = System.getenv(JMX_CONNECTOR_CREDENTIALS);
                if (value != null) {
                     try {
                         String username = value.substring(0, value.indexOf(":"));
                         String password = value.substring(value.indexOf(":") + 1);
                         String[] credentials = new String[]{username, password};
                         env.put(JMXConnector.CREDENTIALS, credentials);
                     } catch (StringIndexOutOfBoundsException e) {
                         // Abort on authentication failures
                         logger.severe("Malformed environment variable (expected username:password): '" + JMX_CONNECTOR_CREDENTIALS + "'");
                         System.exit(1);
                     }
                }
            }
        }

        // Constructor
        public JVMWorker(final String url, Set<String> filters) {
            this.strUrl = url;
            this.filters = filters;
        }

        // Runner
        public void run() {
            logger.finest("Thread " + Thread.currentThread().getId() + " checking JVM " + strUrl);
            try {
                setupAuthentication();
                String address = strUrl;
                JMXServiceURL url = new JMXServiceURL(address);
                JMXConnector connector = JMXConnectorFactory.connect(url, env);
                MBeanServerConnection connection = connector.getMBeanServerConnection();

                // Do the actual work
                fetchAndPrintItems(connection, filters);

            } catch (IOException e) {
                logger.severe(e.toString());
                System.exit(1);
            } catch (SecurityException e) {
                // Abort on authentication failures
                logger.severe(e.getMessage() + " for: ");
                logger.config("Environment variable '" + JMX_CONNECTOR_CREDENTIALS + "' may be used to hold username:password.");
                System.exit(1);
            } catch (Exception e) {
                // Probably something serious
                logger.severe(e.toString());
                e.printStackTrace(System.err);
                System.exit(1);
            }
        }
    }

    // Main
    public static void main(String[] args) throws FileNotFoundException, InterruptedException, IOException {
        // Logging
        setupLogging();

        // Configuration
        parseCommandLine(args);         // Look for config file from the command line
        parseConfigurationFile(configFile);
        parseCommandLine(args);         // Command line always overrides config file

        // Prepare filters and target URLs
        Set<String> jvms = prepareJVMURLs();
        Set<String> filters = getMBeanFilters();

        // Initialization ok, print the JSON header
        System.out.println("{\n    \"metrics\": [");

        // Main
        logger.finest("Starting JVM checks...");
        long startTime = System.currentTimeMillis();

        for (String url: jvms) {
            new JVMWorker(url, filters).run();
        }

        // Done, print the JSON footer
        System.out.println("\n    ]\n}");

        long timeEstimate = System.currentTimeMillis() - startTime;
        logger.fine("All JVMs checked in " + timeEstimate / 1000.0 + "s.");
        logger.fine("Exiting.");
    }
}
