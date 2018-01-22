# MBean2JSON

[![License: Apache v2](https://img.shields.io/badge/license-Apache%20v2-brightgreen.svg)](https://www.apache.org/licenses/LICENSE-2.0)

## Description

A trivial helper utility to generate JXM MBeans / JSON mapping files
to allow creating configuration files for
[PCP](http://pcp.io/)
/
[Parfait](https://github.com/performancecopilot/parfait).

## Example

The tool has been tested with OpenJDK 1.8 running HelloWorld, Tomcat
8.5, and WildFly 10.1. It may or may not work with other components.
(With WildFly _jboss-cli-client.jar_ will be needed to use the
remote HTTP protocol.)

The [example.txt](example.txt) file is the output when run against an
OpenJDK 1.8 JVM running a HelloWorld type program. The _--compat-only_
option is currently needed as Parfait does not support all the data
types used for JVM JMX metrics (or for other components, like WildFly).

```
$ javac MBean2JSON.java
$ vi mbean2json.properties
$ java -Dcom.sun.management.jmxremote=true \
       -Dcom.sun.management.jmxremote.authenticate=false \
       -Dcom.sun.management.jmxremote.local.only=true \
       -Dcom.sun.management.jmxremote.port=9875 \
       -Dcom.sun.management.jmxremote.ssl=false \
       HelloWorld &
$ java MBean2JSON --compat-only > example.txt
```

## Future Directions

None; the tool should be either merged into Parfait or Parfait should
provide similar functionality out of the box.

Please refer to PCP/Parfait pages for more information and latest status
updates.

## License

Apache v2
