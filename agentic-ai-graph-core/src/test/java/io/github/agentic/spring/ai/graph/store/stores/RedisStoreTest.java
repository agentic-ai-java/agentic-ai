/*
 * Copyright 2024-2026 the original author or authors.
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
package io.github.agentic.spring.ai.graph.store.stores;

import io.github.agentic.spring.ai.graph.store.NamespaceListRequest;
import io.github.agentic.spring.ai.graph.store.StoreItem;
import io.github.agentic.spring.ai.graph.store.StoreSearchRequest;
import io.github.agentic.spring.ai.graph.store.StoreSearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for RedisStore implementation.
 *
 * @author Spring AI Alibaba
 */
class RedisStoreTest {

	private static final String LEGACY_PREFIX = "spring:ai:alibaba:store:";

	private RedisStore redisStore;

	@BeforeEach
	void setUp() {
		redisStore = new RedisStore();
	}

	@Test
	void testPutAndGetItem() {
		// Given
		List<String> namespace = List.of("users", "user123");
		String key = "preferences";
		Map<String, Object> value = Map.of("theme", "dark", "language", "en-US");
		StoreItem item = StoreItem.of(namespace, key, value);

		// When
		redisStore.putItem(item);
		Optional<StoreItem> retrieved = redisStore.getItem(namespace, key);

		// Then
		assertThat(retrieved).isPresent();
		assertThat(retrieved.get().getNamespace()).isEqualTo(namespace);
		assertThat(retrieved.get().getKey()).isEqualTo(key);
		assertThat(retrieved.get().getValue()).isEqualTo(value);
	}

	@Test
	void defaultStoreWritesItemsWithAgenticPrefix() throws Exception {
		redisStore.putItem(StoreItem.of(List.of("users", "user123"), "preferences", Map.of("theme", "dark")));

		assertThat(storage().keySet()).allMatch(key -> key.startsWith("agentic:spring:ai:store:"));
	}

	@Test
	void defaultStoreReadsLegacyPrefixedItems() throws Exception {
		StoreItem legacyItem = StoreItem.of(List.of("users", "legacy"), "preferences", Map.of("theme", "dark"));
		putRawItem(LEGACY_PREFIX, legacyItem);

		assertThat(redisStore.getItem(legacyItem.getNamespace(), legacyItem.getKey())).isPresent();
		assertThat(redisStore.searchItems(StoreSearchRequest.builder().build()).getItems()).hasSize(1);
		assertThat(redisStore.listNamespaces(NamespaceListRequest.builder().build())).contains("users", "users/legacy");
		assertThat(redisStore.size()).isEqualTo(1);
	}

	@Test
	void updatingLegacyItemMigratesItToAgenticPrefix() throws Exception {
		List<String> namespace = List.of("users", "legacy");
		String key = "preferences";
		putRawItem(LEGACY_PREFIX, StoreItem.of(namespace, key, Map.of("theme", "light")));

		redisStore.putItem(StoreItem.of(namespace, key, Map.of("theme", "dark")));

		assertThat(redisStore.getItem(namespace, key)).get().extracting(StoreItem::getValue)
				.isEqualTo(Map.of("theme", "dark"));
		assertThat(storage().keySet()).noneMatch(redisKey -> redisKey.startsWith(LEGACY_PREFIX));
		assertThat(redisStore.size()).isEqualTo(1);
	}

	@Test
	void deleteAndClearRemoveLegacyPrefixedItems() throws Exception {
		StoreItem deletedItem = StoreItem.of(List.of("users", "legacy"), "deleted", Map.of("value", 1));
		StoreItem clearedItem = StoreItem.of(List.of("users", "legacy"), "cleared", Map.of("value", 2));
		putRawItem(LEGACY_PREFIX, deletedItem);
		putRawItem(LEGACY_PREFIX, clearedItem);

		assertThat(redisStore.deleteItem(deletedItem.getNamespace(), deletedItem.getKey())).isTrue();
		assertThat(redisStore.getItem(deletedItem.getNamespace(), deletedItem.getKey())).isEmpty();

		redisStore.clear();
		assertThat(redisStore.isEmpty()).isTrue();
		assertThat(storage()).isEmpty();
	}

	@Test
	void testDeleteItem() {
		// Given
		List<String> namespace = List.of("test", "namespace");
		String key = "test_key";
		Map<String, Object> value = Map.of("data", "test_value");
		StoreItem item = StoreItem.of(namespace, key, value);

		redisStore.putItem(item);
		assertThat(redisStore.getItem(namespace, key)).isPresent();

		// When
		boolean deleted = redisStore.deleteItem(namespace, key);

		// Then
		assertThat(deleted).isTrue();
		assertThat(redisStore.getItem(namespace, key)).isEmpty();
		assertThat(redisStore.deleteItem(namespace, key)).isFalse(); // Already deleted
	}

	@Test
	void testSearchItems() {
		// Given
		setupTestData();

		// When - search all items
		StoreSearchRequest request = StoreSearchRequest.builder().build();
		StoreSearchResult result = redisStore.searchItems(request);

		// Then
		assertThat(result.getItems()).hasSize(3);
		assertThat(result.getTotalCount()).isEqualTo(3);
	}

	@Test
	void testListNamespaces() {
		// Given
		setupTestData();

		// When
		NamespaceListRequest request = NamespaceListRequest.builder().build();
		List<String> namespaces = redisStore.listNamespaces(request);

		// Then
		assertThat(namespaces).hasSize(6);
		assertThat(namespaces).containsExactlyInAnyOrder("users", "users/admin", "users/user1",
				"users/user1/preferences", "users/user2", "users/user2/preferences");
	}

	@Test
	void testValidationErrors() {
		// Test null item
		assertThrows(IllegalArgumentException.class, () -> redisStore.putItem(null));

		// Test null namespace
		assertThrows(IllegalArgumentException.class, () -> redisStore.getItem(null, "key"));

		// Test null key
		assertThrows(IllegalArgumentException.class, () -> redisStore.getItem(List.of("namespace"), null));

		// Test empty key
		assertThrows(IllegalArgumentException.class, () -> redisStore.getItem(List.of("namespace"), ""));

		// Test null search request
		assertThrows(IllegalArgumentException.class, () -> redisStore.searchItems(null));

		// Test null namespace request
		assertThrows(IllegalArgumentException.class, () -> redisStore.listNamespaces(null));
	}

	@Test
	void testSizeAndClear() {
		// Given
		assertThat(redisStore.isEmpty()).isTrue();
		assertThat(redisStore.size()).isEqualTo(0);

		setupTestData();

		// When
		assertThat(redisStore.isEmpty()).isFalse();
		assertThat(redisStore.size()).isEqualTo(3);

		redisStore.clear();

		// Then
		assertThat(redisStore.isEmpty()).isTrue();
		assertThat(redisStore.size()).isEqualTo(0);
	}

	@Test
	void testSearchByNamespace() {
		// Given
		setupTestData();

		// When
		StoreSearchRequest request = StoreSearchRequest.builder().namespace(List.of("users", "user1")).build();
		StoreSearchResult result = redisStore.searchItems(request);

		// Then
		assertThat(result.getItems()).hasSize(1);
		List<String> namespace = result.getItems().get(0).getNamespace();
		assertThat(namespace).hasSize(3);
		assertThat(namespace.get(0)).isEqualTo("users");
		assertThat(namespace.get(1)).isEqualTo("user1");
		assertThat(namespace.get(2)).isEqualTo("preferences");
	}

	@Test
	void testSearchByQuery() {
		// Given
		setupTestData();

		// When
		StoreSearchRequest request = StoreSearchRequest.builder().query("Administrator").build();
		StoreSearchResult result = redisStore.searchItems(request);

		// Then
		assertThat(result.getItems()).hasSize(1);
		assertThat(result.getItems().get(0).getValue().get("name")).isEqualTo("Administrator");
	}

	@Test
	void testSearchWithFilters() {
		// Given
		setupTestData();

		// When
		StoreSearchRequest request = StoreSearchRequest.builder().filter(Map.of("theme", "dark")).build();
		StoreSearchResult result = redisStore.searchItems(request);

		// Then
		assertThat(result.getItems()).hasSize(1);
		assertThat(result.getItems().get(0).getValue().get("theme")).isEqualTo("dark");
	}

	@Test
	void testPagination() {
		// Given
		setupTestData();

		// When
		StoreSearchRequest request = StoreSearchRequest.builder().offset(1).limit(1).build();
		StoreSearchResult result = redisStore.searchItems(request);

		// Then
		assertThat(result.getItems()).hasSize(1);
		assertThat(result.getOffset()).isEqualTo(1);
		assertThat(result.getLimit()).isEqualTo(1);
		assertThat(result.getTotalCount()).isEqualTo(3);
	}

	@Test
	void testUpdateExistingItem() {
		// Given
		List<String> namespace = List.of("test");
		String key = "updatable_item";
		Map<String, Object> originalValue = Map.of("version", 1);
		StoreItem originalItem = StoreItem.of(namespace, key, originalValue);

		redisStore.putItem(originalItem);

		// When - update the same item
		Map<String, Object> updatedValue = Map.of("version", 2, "updated", true);
		StoreItem updatedItem = StoreItem.of(namespace, key, updatedValue);
		redisStore.putItem(updatedItem);

		// Then
		Optional<StoreItem> retrieved = redisStore.getItem(namespace, key);
		assertThat(retrieved).isPresent();
		assertThat(retrieved.get().getValue()).isEqualTo(updatedValue);
		assertThat(redisStore.size()).isEqualTo(1); // Should still be 1 item
	}

	private void setupTestData() {
		// User admin data
		redisStore.putItem(
				StoreItem.of(List.of("users", "admin"), "profile", Map.of("name", "Administrator", "role", "admin")));

		// User1 preferences
		redisStore.putItem(StoreItem.of(List.of("users", "user1", "preferences"), "ui_settings",
				Map.of("theme", "dark", "language", "en-US")));

		// User2 preferences
		redisStore.putItem(StoreItem.of(List.of("users", "user2", "preferences"), "ui_settings",
				Map.of("theme", "light", "language", "zh-CN")));
	}

	@SuppressWarnings("unchecked")
	private Map<String, String> storage() throws Exception {
		Field storageField = RedisStore.class.getDeclaredField("redisLikeStorage");
		storageField.setAccessible(true);
		return (Map<String, String>) storageField.get(redisStore);
	}

	private void putRawItem(String prefix, StoreItem item) throws Exception {
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		storage().put(prefix + redisStore.createStoreKey(item.getNamespace(), item.getKey()),
				objectMapper.writeValueAsString(item));
	}

}
