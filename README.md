Wildfly HTTP Client
===========

Wildfly HTTP Client is a client libraries that support EJB, Naming and Transactions over HTTP. It consists of:

* Request Timeouts
* Digest Auth
* Multiple URL's for naming
* Multiplexing support
* SSL testing

Building From Source
--------------------

```console
$ git clone https://github.com/wildfly/wildfly-http-client
```

Setup the JBoss Maven Repository
--------------------------------

To use dependencies from JBoss.org, you need to add the JBoss Maven Repositories to your Maven settings.xml. For details see [Maven Getting Started - Users](https://developer.jboss.org/docs/DOC-15169)


Build with Maven
----------------

The command below builds the project and runs the embedded suite.

```console
$ mvn clean install
```

Issue Tracking
--------------

Bugs and features are tracked at https://issues.jboss.org/browse/WEJBHTTP

Contributions
-------------

All new features and enhancements should be submitted against main branch only.
