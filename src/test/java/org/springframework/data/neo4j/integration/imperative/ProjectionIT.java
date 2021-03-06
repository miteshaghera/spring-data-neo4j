/*
 * Copyright 2011-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.neo4j.integration.imperative;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.Values;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.neo4j.config.AbstractNeo4jConfig;
import org.springframework.data.neo4j.integration.shared.common.NamesOnly;
import org.springframework.data.neo4j.integration.shared.common.NamesOnlyDto;
import org.springframework.data.neo4j.integration.shared.common.Person;
import org.springframework.data.neo4j.integration.shared.common.PersonSummary;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.data.neo4j.test.Neo4jExtension;
import org.springframework.data.neo4j.test.Neo4jIntegrationTest;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * @author Gerrit Meier
 * @author Michael J. Simons
 */
@Neo4jIntegrationTest
class ProjectionIT {

	private static final String FIRST_NAME = "Hans";
	private static final String FIRST_NAME2 = "Lieschen";
	private static final String LAST_NAME = "Mueller";
	private static final String CITY = "Braunschweig";

	private static Neo4jExtension.Neo4jConnectionSupport neo4jConnectionSupport;

	private final Driver driver;

	@Autowired
	ProjectionIT(Driver driver) {
		this.driver = driver;
	}

	@BeforeEach
	void setup() {
		Session session = driver.session();
		Transaction transaction = session.beginTransaction();

		transaction.run("MATCH (n) detach delete n");

		for (Map.Entry<String, String> person : new Map.Entry[] {
				new AbstractMap.SimpleEntry(FIRST_NAME, LAST_NAME),
				new AbstractMap.SimpleEntry(FIRST_NAME2, LAST_NAME),
		}) {
			transaction.run(" MERGE (address:Address{city: $city})"
							+ "CREATE (:Person{firstName: $firstName, lastName: $lastName})"
							+ "-[:LIVES_AT]-> (address)",
					Values.parameters("firstName", person.getKey(), "lastName", person.getValue(), "city", CITY));
		}

		transaction.commit();
		transaction.close();
		session.close();
	}

	@Test
	void loadNamesOnlyProjection(@Autowired ProjectionPersonRepository repository) {

		Collection<NamesOnly> people = repository.findByLastName(LAST_NAME);
		assertThat(people).hasSize(2);

		assertThat(people).extracting(NamesOnly::getFirstName).containsExactlyInAnyOrder(FIRST_NAME, FIRST_NAME2);
		assertThat(people).extracting(NamesOnly::getLastName).containsOnly(LAST_NAME);

		assertThat(people).extracting(NamesOnly::getFullName).containsExactlyInAnyOrder(FIRST_NAME + " " + LAST_NAME, FIRST_NAME2 + " " + LAST_NAME);
	}

	@Test
	void loadPersonSummaryProjection(@Autowired ProjectionPersonRepository repository) {
		Collection<PersonSummary> people = repository.findByFirstName(FIRST_NAME);
		assertThat(people).hasSize(1);

		PersonSummary person = people.iterator().next();
		assertThat(person.getFirstName()).isEqualTo(FIRST_NAME);
		assertThat(person.getLastName()).isEqualTo(LAST_NAME);
		assertThat(person.getAddress()).isNotNull();

		PersonSummary.AddressSummary address = person.getAddress();
		assertThat(address.getCity()).isEqualTo(CITY);

	}

	@Test
	void loadNamesOnlyDtoProjection(@Autowired ProjectionPersonRepository repository) {
		Collection<NamesOnlyDto> people = repository.findByFirstNameAndLastName(FIRST_NAME, LAST_NAME);
		assertThat(people).hasSize(1);

		NamesOnlyDto person = people.iterator().next();
		assertThat(person.getFirstName()).isEqualTo(FIRST_NAME);
		assertThat(person.getLastName()).isEqualTo(LAST_NAME);

	}

	@Test
	void findDynamicProjectionForNamesOnly(@Autowired ProjectionPersonRepository repository) {
		Collection<NamesOnly> people = repository.findByLastNameAndFirstName(LAST_NAME, FIRST_NAME, NamesOnly.class);
		assertThat(people).hasSize(1);

		NamesOnly person = people.iterator().next();
		assertThat(person.getFirstName()).isEqualTo(FIRST_NAME);
		assertThat(person.getLastName()).isEqualTo(LAST_NAME);

		String expectedFullName = FIRST_NAME + " " + LAST_NAME;
		assertThat(person.getFullName()).isEqualTo(expectedFullName);

	}

	@Test
	void findDynamicProjectionForPersonSummary(@Autowired ProjectionPersonRepository repository) {
		Collection<PersonSummary> people = repository.findByLastNameAndFirstName(LAST_NAME, FIRST_NAME,
				PersonSummary.class);
		assertThat(people).hasSize(1);

		PersonSummary person = people.iterator().next();
		assertThat(person.getFirstName()).isEqualTo(FIRST_NAME);
		assertThat(person.getLastName()).isEqualTo(LAST_NAME);
		assertThat(person.getAddress()).isNotNull();

		PersonSummary.AddressSummary address = person.getAddress();
		assertThat(address.getCity()).isEqualTo(CITY);

	}

	@Test
	void findDynamicProjectionForNamesOnlyDto(@Autowired ProjectionPersonRepository repository) {
		Collection<NamesOnlyDto> people = repository.findByLastNameAndFirstName(LAST_NAME, FIRST_NAME, NamesOnlyDto.class);
		assertThat(people).hasSize(1);

		NamesOnlyDto person = people.iterator().next();
		assertThat(person.getFirstName()).isEqualTo(FIRST_NAME);
		assertThat(person.getLastName()).isEqualTo(LAST_NAME);

	}

	@Test // GH-2139
	void projectionsShouldBePaginatable(@Autowired ProjectionPersonRepository repository) {

		Page<NamesOnly> people = repository.findAllProjectedBy(PageRequest.of(1, 1, Sort.by("firstName").descending()));
		assertThat(people.hasPrevious()).isTrue();
		assertThat(people.hasNext()).isFalse();
		assertThat(people).hasSize(1);
		assertThat(people).extracting(NamesOnly::getFullName).containsExactly(FIRST_NAME + " " + LAST_NAME);
	}

	@Test // GH-2139
	void projectionsShouldBeSliceable(@Autowired ProjectionPersonRepository repository) {

		Slice<NamesOnly> people = repository.findSliceProjectedBy(PageRequest.of(1, 1, Sort.by("firstName").descending()));
		assertThat(people.hasPrevious()).isTrue();
		assertThat(people.hasNext()).isFalse();
		assertThat(people).hasSize(1);
		assertThat(people).extracting(NamesOnly::getFullName).containsExactly(FIRST_NAME + " " + LAST_NAME);
	}

	interface ProjectionPersonRepository extends Neo4jRepository<Person, Long> {

		Collection<NamesOnly> findByLastName(String lastName);

		Page<NamesOnly> findAllProjectedBy(Pageable pageable);

		Slice<NamesOnly> findSliceProjectedBy(Pageable pageable);

		Collection<PersonSummary> findByFirstName(String firstName);

		Collection<NamesOnlyDto> findByFirstNameAndLastName(String firstName, String lastName);

		<T> Collection<T> findByLastNameAndFirstName(String lastName, String firstName, Class<T> projectionClass);
	}

	@Configuration
	@EnableNeo4jRepositories(considerNestedRepositories = true)
	@EnableTransactionManagement
	static class Config extends AbstractNeo4jConfig {

		@Bean
		public Driver driver() {
			return neo4jConnectionSupport.getDriver();
		}

	}
}
