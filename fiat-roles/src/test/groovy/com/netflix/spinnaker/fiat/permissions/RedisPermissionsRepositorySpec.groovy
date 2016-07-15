/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.fiat.permissions

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.fiat.model.UserPermission
import com.netflix.spinnaker.fiat.model.resources.Account
import com.netflix.spinnaker.fiat.model.resources.Application
import com.netflix.spinnaker.fiat.redis.JedisSource
import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import redis.clients.jedis.Jedis
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject

class RedisPermissionsRepositorySpec extends Specification {

  static String prefix = "unittests"

  @Shared
  @AutoCleanup("destroy")
  EmbeddedRedis embeddedRedis

  @Shared
  ObjectMapper objectMapper = new ObjectMapper()

  @Shared
  Jedis jedis

  @Subject
  RedisPermissionsRepository repo

  def setupSpec() {
    embeddedRedis = EmbeddedRedis.embed()
    jedis = embeddedRedis.jedis
    jedis.flushDB()
  }

  def setup() {
    JedisSource js = new JedisSource() {
      @Override
      Jedis getJedis() {
        return embeddedRedis.jedis
      }
    }
    repo = new RedisPermissionsRepository()
        .setPrefix(prefix)
        .setObjectMapper(objectMapper)
        .setJedisSource(js)
  }

  def cleanup() {
    jedis.flushDB()
  }

  def "should put the specified permission in redis"() {
    setup:
    Account account1 = new Account().setName("account1")
    Application app1 = new Application().setName("app1")

    when:
    repo.put(new UserPermission()
                 .setId("testUser1")
                 .setAccounts([account1] as Set)
                 .setApplications([app1] as Set))

    then:
    jedis.smembers("unittests:users") == ["testUser1"] as Set
    jedis.hgetAll("unittests:permissions:testUser1:accounts") ==
        ['account1': '{"name":"account1","requiredGroupMembership":[]}']
    jedis.hgetAll("unittests:permissions:testUser1:applications") ==
        ['app1': '{"name":"app1","requiredGroupMembership":[]}']
  }

  def "should remove permission that has been revoked"() {
    setup:
    jedis.sadd("unittest:users", "testUser2");
    jedis.hset("unittests:permissions:testUser2:accounts",
               "account2",
               '{"name":"account2","requiredGroupMembership":[]}')
    jedis.hset("unittests:permissions:testUser2:applications",
               "app2",
               '{"name":"app2","requiredGroupMembership":[]}')

    when:
    repo.put(new UserPermission()
                 .setId("testUser1")
                 .setAccounts([] as Set)
                 .setApplications([] as Set))

    then:
    jedis.hgetAll("unittests:permissions:testUser1:accounts") == [:]
    jedis.hgetAll("unittests:permissions:testUser1:applications") == [:]
  }

  def "should get the permission out of redis"() {
    setup:
    jedis.sadd("unittest:users", "testUser2");
    jedis.hset("unittests:permissions:testUser2:accounts",
               "account2",
               '{"name":"account2","requiredGroupMembership":[]}')
    jedis.hset("unittests:permissions:testUser2:applications",
               "app2",
               '{"name":"app2","requiredGroupMembership":[]}')

    when:
    def result = repo.get("testUser2").get()

    then:
    result
    result.id == "testUser2"
    result.accounts == [new Account().setName("account2")] as Set
    result.applications == [new Application().setName("app2")] as Set
  }

  def "should get all users from redis"() {
    setup:
    jedis.sadd("unittests:users", "testUser3", "testUser4");
    jedis.hset("unittests:permissions:testUser3:accounts",
               "account3",
               '{"name":"account3","requiredGroupMembership":[]}')
    jedis.hset("unittests:permissions:testUser4:accounts",
               "account4",
               '{"name":"account4","requiredGroupMembership":["abc"]}')
    jedis.hset("unittests:permissions:testUser3:applications",
               "app3",
               '{"name":"app3","requiredGroupMembership":[]}')
    jedis.hset("unittests:permissions:testUser4:applications",
               "app4",
               '{"name":"app4","requiredGroupMembership":["abc"]}')

    and:
    Account account3 = new Account().setName("account3")
    Application app3 = new Application().setName("app3")
    Account account4 = new Account().setName("account4").setRequiredGroupMembership(["abc"])
    Application app4 = new Application().setName("app4").setRequiredGroupMembership(["abc"])

    when:
    def result = repo.getAllById();

    then:
    result
    result.size() == 2
    result["testUser3"] == new UserPermission().setId("testUser3")
                                               .setAccounts([account3] as Set)
                                               .setApplications([app3] as Set)
    result["testUser4"] == new UserPermission().setId("testUser4")
                                               .setAccounts([account4] as Set)
                                               .setApplications([app4] as Set)
  }

  def "should delete the specified user"() {
    given:
    jedis.keys("*").size() == 0

    Account account1 = new Account().setName("account1")
    Application app1 = new Application().setName("app1")

    when:
    repo.put(new UserPermission()
                 .setId("testUser1")
                 .setAccounts([account1] as Set)
                 .setApplications([app1] as Set))

    then:
    jedis.keys("*").size() == 3 // users, accounts, and applications.

    when:
    repo.remove("testUser1")

    then:
    jedis.keys("*").size() == 0
  }
}
