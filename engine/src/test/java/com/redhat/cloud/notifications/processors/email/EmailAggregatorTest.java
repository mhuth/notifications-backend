package com.redhat.cloud.notifications.processors.email;

import com.redhat.cloud.notifications.TestHelpers;
import com.redhat.cloud.notifications.config.EngineConfig;
import com.redhat.cloud.notifications.db.ResourceHelpers;
import com.redhat.cloud.notifications.db.repositories.EmailAggregationRepository;
import com.redhat.cloud.notifications.db.repositories.EndpointRepository;
import com.redhat.cloud.notifications.models.Application;
import com.redhat.cloud.notifications.models.EmailAggregationKey;
import com.redhat.cloud.notifications.models.Endpoint;
import com.redhat.cloud.notifications.models.EndpointType;
import com.redhat.cloud.notifications.models.EventAggregationCriteria;
import com.redhat.cloud.notifications.models.EventType;
import com.redhat.cloud.notifications.models.SystemSubscriptionProperties;
import com.redhat.cloud.notifications.recipients.User;
import com.redhat.cloud.notifications.recipients.recipientsresolver.RecipientsResolverService;
import com.redhat.cloud.notifications.recipients.recipientsresolver.pojo.RecipientsQuery;
import com.redhat.cloud.notifications.transformers.BaseTransformer;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.redhat.cloud.notifications.models.SubscriptionType.DAILY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class EmailAggregatorTest {

    @InjectSpy
    EmailAggregationRepository emailAggregationRepository;

    @InjectMock
    @RestClient
    RecipientsResolverService recipientsResolverService;

    @InjectSpy
    EmailAggregator emailAggregator;

    @Inject
    ResourceHelpers resourceHelpers;

    @InjectMock
    EndpointRepository endpointRepository;

    @Inject
    CacheManager cacheManager;

    @InjectMock
    EngineConfig engineConfig;

    Application application;
    EventType eventType1;
    EventType eventType2;
    final EmailAggregationKey AGGREGATION_KEY = new EmailAggregationKey("org-1", "rhel", "policies");

    @BeforeEach
    void beforeEach() {
        emailAggregator.maxPageSize = 5;
        clearCachedData("recipients-resolver-results");
        clearInvocations(recipientsResolverService);
        emailAggregationRepository.purgeOldAggregation(AGGREGATION_KEY, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        resourceHelpers.clearEvents();
    }

    @AfterEach
    void afterEach() {
        resourceHelpers.deleteEventTypeEmailSubscription("org-1", "user-2", eventType2, DAILY);
        resourceHelpers.deleteEventTypeEmailSubscription("org-1", "user-2", eventType1, DAILY);
        resourceHelpers.clearEvents();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldTestRecipientsFromSubscription(boolean isAggregationBasedOnEvents) {
        when(engineConfig.isAggregationBasedOnEventEnabled(anyString())).thenReturn(isAggregationBasedOnEvents);

        // init test environment
        application = resourceHelpers.findApp("rhel", "policies");
        eventType1 = resourceHelpers.findOrCreateEventType(application.getId(), TestHelpers.eventType);
        eventType2 = resourceHelpers.findOrCreateEventType(application.getId(), "not-used");
        resourceHelpers.findOrCreateEventType(application.getId(), "event-type-2");
        resourceHelpers.createEventTypeEmailSubscription("org-1", "user-2", eventType2, DAILY);

        Endpoint endpoint = new Endpoint();
        endpoint.setProperties(new SystemSubscriptionProperties());
        endpoint.setType(EndpointType.EMAIL_SUBSCRIPTION);

        when(endpointRepository.getTargetEmailSubscriptionEndpoints(anyString(), any(UUID.class))).thenReturn(List.of(endpoint));
        when(recipientsResolverService.getRecipients(any(RecipientsQuery.class))).then(parameters -> {
            RecipientsQuery query = parameters.getArgument(0);
            Set<String> users = query.subscribers;
            return users.stream().map(usrStr -> {
                User usr = new User();
                usr.setEmail(usrStr);
                return usr;
            }).collect(Collectors.toSet());
        });

        // Test user subscription based on event type
        Map<User, Map<String, Object>> result = null;
        result = aggregate(isAggregationBasedOnEvents);
        if (isAggregationBasedOnEvents) {
            verify(emailAggregationRepository, times(1)).getEmailAggregationBasedOnEvent(any(EventAggregationCriteria.class), any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt());
            verify(emailAggregationRepository, times(1)).getEmailAggregationBasedOnEvent(any(EventAggregationCriteria.class), any(LocalDateTime.class), any(LocalDateTime.class), eq(0), eq(emailAggregator.maxPageSize));
            verify(emailAggregationRepository, never()).getEmailAggregation(any(EmailAggregationKey.class), any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt());
        } else {
            verify(emailAggregationRepository, times(1)).getEmailAggregation(any(EmailAggregationKey.class), any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt());
            verify(emailAggregationRepository, times(1)).getEmailAggregation(any(EmailAggregationKey.class), any(LocalDateTime.class), any(LocalDateTime.class), eq(0), eq(emailAggregator.maxPageSize));
            verify(emailAggregationRepository, never()).getEmailAggregationBasedOnEvent(any(EventAggregationCriteria.class), any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt());
        }

        // nobody subscribed to the right event type yet
        assertEquals(0, result.size());
        clearInvocations(recipientsResolverService); // just reset mockito counter
        clearInvocations(emailAggregationRepository);
        resourceHelpers.createEventTypeEmailSubscription("org-1", "user-2", eventType1, DAILY);
        // because after the previous aggregate() call the email_aggregation DB table was not purged, we already have 4 records on database
        result = aggregate(isAggregationBasedOnEvents);
        if (isAggregationBasedOnEvents) {
            verify(emailAggregationRepository, times(2)).getEmailAggregationBasedOnEvent(any(EventAggregationCriteria.class), any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt());
            verify(emailAggregationRepository, times(1)).getEmailAggregationBasedOnEvent(any(EventAggregationCriteria.class), any(LocalDateTime.class), any(LocalDateTime.class), eq(0), eq(emailAggregator.maxPageSize));
            verify(emailAggregationRepository, times(1)).getEmailAggregationBasedOnEvent(any(EventAggregationCriteria.class), any(LocalDateTime.class), any(LocalDateTime.class), eq(5), eq(emailAggregator.maxPageSize));
            verify(emailAggregationRepository, never()).getEmailAggregation(any(EmailAggregationKey.class), any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt());
        } else {
            verify(emailAggregationRepository, times(2)).getEmailAggregation(any(EmailAggregationKey.class), any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt());
            verify(emailAggregationRepository, times(1)).getEmailAggregation(any(EmailAggregationKey.class), any(LocalDateTime.class), any(LocalDateTime.class), eq(0), eq(emailAggregator.maxPageSize));
            verify(emailAggregationRepository, times(1)).getEmailAggregation(any(EmailAggregationKey.class), any(LocalDateTime.class), any(LocalDateTime.class), eq(5), eq(emailAggregator.maxPageSize));
            verify(emailAggregationRepository, never()).getEmailAggregationBasedOnEvent(any(EventAggregationCriteria.class), any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt());
        }
        assertEquals(1, result.size());
        User user = result.keySet().stream().findFirst().get();
        assertTrue(user.getEmail().equals("user-2"));
        assertEquals(8, ((LinkedHashMap) result.get(user).get("policies")).size());
        verify(recipientsResolverService, times(1)).getRecipients(any(RecipientsQuery.class));
    }

    private Map<User, Map<String, Object>> aggregate(boolean useEventAggregationCriteria) {
        Map<User, Map<String, Object>> result = new HashMap<>();
        emailAggregationRepository.addEmailAggregation(TestHelpers.createEmailAggregation("org-1", "rhel", "policies", RandomStringUtils.random(10), RandomStringUtils.random(10)));
        emailAggregationRepository.addEmailAggregation(TestHelpers.createEmailAggregation("org-1", "rhel", "policies", RandomStringUtils.random(10), RandomStringUtils.random(10)));
        emailAggregationRepository.addEmailAggregation(TestHelpers.createEmailAggregation("org-1", "rhel", "policies", RandomStringUtils.random(10), RandomStringUtils.random(10)));
        emailAggregationRepository.addEmailAggregation(TestHelpers.createEmailAggregation("org-1", "rhel", "policies", RandomStringUtils.random(10), RandomStringUtils.random(10)));

        emailAggregationRepository.addEmailAggregation(TestHelpers.createEmailAggregation("org-2", "rhel", "policies", RandomStringUtils.random(10), RandomStringUtils.random(10)));

        for (int i = 0; i < 4; i++) {
            JsonObject payload = TestHelpers.createEmailAggregation("org-1", "rhel", "policies", RandomStringUtils.random(10), RandomStringUtils.random(10)).getPayload();
            // the base transformer adds a "source" element which should not be present in an original event payload
            payload.remove(BaseTransformer.SOURCE);
            resourceHelpers.addEventEmailAggregation("org-1", "rhel", "policies", payload, false);
        }
        JsonObject payload = TestHelpers.createEmailAggregation("org-2", "rhel", "policies", RandomStringUtils.random(10), RandomStringUtils.random(10)).getPayload();
        // the base transformer adds a "source" element which should not be present in an original event payload
        payload.remove(BaseTransformer.SOURCE);
        resourceHelpers.addEventEmailAggregation("org-2", "rhel", "policies", payload, false);

        EmailAggregationKey aggregationKey = AGGREGATION_KEY;
        if (useEventAggregationCriteria) {
            Application policiesApp = resourceHelpers.findApp("rhel", "policies");
            aggregationKey = new EventAggregationCriteria(AGGREGATION_KEY.getOrgId(), policiesApp.getBundleId(), policiesApp.getId(), AGGREGATION_KEY.getBundle(), AGGREGATION_KEY.getApplication());
        }
        result.putAll(emailAggregator.getAggregated(application.getId(), aggregationKey, DAILY, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1)));
        return result;
    }

    public void clearCachedData(String cacheName) {
        Optional<Cache> cache = cacheManager.getCache(cacheName);
        if (cache.isPresent()) {
            cache.get().invalidateAll().await().indefinitely();
        }
    }
}
