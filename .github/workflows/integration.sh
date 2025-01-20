echo "INTEGRATION TESTS"

WILDFLY_HTTP_REPOSITORY=$1
WILDFLY_HTTP_BRANCH=$2

git clone --depth=1 https://github.com/wildfly/ejb-client-testsuite

cd ejb-client-testsuite

mvn -B -ntp package -DspecificModule=prepare -Dhttp.client.repository=${WILDFLY_HTTP_REPOSITORY} -Dhttp.client.branch=${WILDFLY_HTTP_BRANCH}
mvn -B -ntp dependency:tree clean verify --fail-at-end

