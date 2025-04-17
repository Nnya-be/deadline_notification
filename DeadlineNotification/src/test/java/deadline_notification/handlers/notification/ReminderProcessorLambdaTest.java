package deadline_notification.handlers.notification;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.*;

import static org.mockito.Mockito.*;

class ReminderProcessorLambdaTest {

    @Mock
    private DynamoDbClient mockDynamoDbClient;

    @Mock
    private CognitoIdentityProviderClient mockCognitoClient;

    @Mock
    private SnsClient mockSnsClient;

    @InjectMocks
    private ReminderProcessorLambda lambda;

    @Captor
    private ArgumentCaptor<PublishRequest> publishCaptor;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        System.setProperty("USER_POOL_ID", "test-pool");
        System.setProperty("TABLE_NAME", "test-table");
        System.setProperty("SNS_TOPIC_ARN", "arn:aws:sns:region:123456789012:topic");
    }

    private ScheduledEvent mockScheduledEvent(String taskId) {
        ScheduledEvent event = new ScheduledEvent();
        Map<String, Object> detail = new HashMap<>();
        detail.put("taskId", taskId);
        event.setDetail(detail);
        return event;
    }

    @Test
    void handleRequest_success_sendsNotification() {
        String taskId = "task123";
        String assigneeId = "user123";
        String deadline = "2025-04-20T12:00:00Z";
        String title = "Complete Report";
        String email = "user@example.com";

        Map<String, AttributeValue> taskItem = new HashMap<>();
        taskItem.put("status", AttributeValue.builder().s("active").build());
        taskItem.put("assigneeId", AttributeValue.builder().s(assigneeId).build());
        taskItem.put("title", AttributeValue.builder().s(title).build());
        taskItem.put("deadline", AttributeValue.builder().s(deadline).build());

        when(mockDynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(taskItem).build());

        List<AttributeType> attributes = List.of(AttributeType.builder().name("email").value(email).build());

        when(mockCognitoClient.adminGetUser(any(AdminGetUserRequest.class)))
                .thenReturn(AdminGetUserResponse.builder().userAttributes(attributes).build());

        when(mockSnsClient.publish(any(PublishRequest.class)))
                .thenReturn(null);

        lambda.handleRequest(mockScheduledEvent(taskId), mock(Context.class));

        verify(mockSnsClient).publish(publishCaptor.capture());
        PublishRequest sent = publishCaptor.getValue();

        assert sent.message().contains("Reminder");
        assert sent.message().contains(taskId);
        assert sent.message().contains(title);
    }

    @Test
    void handleRequest_skipsInactiveTask() {
        Map<String, AttributeValue> taskItem = Map.of(
                "status", AttributeValue.builder().s("completed").build()
        );

        when(mockDynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(taskItem).build());

        lambda.handleRequest(mockScheduledEvent("task123"), mock(Context.class));

        verify(mockSnsClient, never()).publish(any(PublishRequest.class));
    }

    @Test
    void handleRequest_missingTask_returnsNull() {
        when(mockDynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        lambda.handleRequest(mockScheduledEvent("missing-task"), mock(Context.class));

        verify(mockSnsClient, never()).publish(any(PublishRequest.class));
    }

    @Test
    void handleRequest_missingEmail_doesNotSendNotification() {
        Map<String, AttributeValue> taskItem = Map.of(
                "status", AttributeValue.builder().s("active").build(),
                "assigneeId", AttributeValue.builder().s("user123").build(),
                "title", AttributeValue.builder().s("Some Task").build(),
                "deadline", AttributeValue.builder().s("2025-04-20T12:00Z").build()
        );

        when(mockDynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(taskItem).build());

        when(mockCognitoClient.adminGetUser(any(AdminGetUserRequest.class)))
                .thenReturn(AdminGetUserResponse.builder().userAttributes(Collections.emptyList()).build());

        lambda.handleRequest(mockScheduledEvent("task123"), mock(Context.class));

        verify(mockSnsClient, never()).publish(any(PublishRequest.class));
    }

    @Test
    void handleRequest_invalidEventPayload_logsError() {
        ScheduledEvent event = new ScheduledEvent();
        lambda.handleRequest(event, mock(Context.class));
        verify(mockDynamoDbClient, never()).getItem(any(GetItemRequest.class));
    }

    @Test
    void getTask_handlesDynamoDbException_gracefully() {
        when(mockDynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenThrow(RuntimeException.class);

        lambda.handleRequest(mockScheduledEvent("task123"), mock(Context.class));
        verify(mockSnsClient, never()).publish(any());
    }

    @Test
    void getUserEmail_handlesCognitoException_gracefully() {
        Map<String, AttributeValue> taskItem = Map.of(
                "status", AttributeValue.builder().s("active").build(),
                "assigneeId", AttributeValue.builder().s("user123").build(),
                "title", AttributeValue.builder().s("Some Task").build(),
                "deadline", AttributeValue.builder().s("2025-04-20T12:00Z").build()
        );

        when(mockDynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(taskItem).build());

        when(mockCognitoClient.adminGetUser(any(AdminGetUserRequest.class)))
                .thenThrow(RuntimeException.class);

        lambda.handleRequest(mockScheduledEvent("task123"), mock(Context.class));

        verify(mockSnsClient, never()).publish(any());
    }

    @Test
    void sendNotification_handlesSnsFailure_gracefully() {
        Map<String, AttributeValue> taskItem = Map.of(
                "status", AttributeValue.builder().s("active").build(),
                "assigneeId", AttributeValue.builder().s("user123").build(),
                "title", AttributeValue.builder().s("Title").build(),
                "deadline", AttributeValue.builder().s("2025-04-20").build()
        );

        when(mockDynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(taskItem).build());

        List<AttributeType> attributes = List.of(AttributeType.builder().name("email").value("test@example.com").build());

        when(mockCognitoClient.adminGetUser(any(AdminGetUserRequest.class)))
                .thenReturn(AdminGetUserResponse.builder().userAttributes(attributes).build());

        doThrow(new RuntimeException("SNS failed")).when(mockSnsClient).publish(any(PublishRequest.class));

        lambda.handleRequest(mockScheduledEvent("task123"), mock(Context.class));

        verify(mockSnsClient).publish(any(PublishRequest.class));
    }
}
