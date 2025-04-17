package deadline_notification.handlers.notification;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.HashMap;
import java.util.Map;

public class ReminderProcessorLambda implements RequestHandler<ScheduledEvent, Void> {

    private static final Logger logger = LoggerFactory.getLogger(ReminderProcessorLambda.class);
    private static final String USER_POOL_ID = System.getenv("USER_POOL_ID");
    private static final String TABLE_NAME = System.getenv("TABLE_NAME");
    private static final String SNS_TOPIC_ARN = System.getenv("SNS_TOPIC_ARN");
    private static final String ACTIVE_STATUS = "active";

    private final DynamoDbClient dynamoDbClient;
    private final CognitoIdentityProviderClient cognitoClient;
    private final SnsClient snsClient;

    public ReminderProcessorLambda() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.cognitoClient = CognitoIdentityProviderClient.create();
        this.snsClient = SnsClient.create();
    }

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        try {
            if (USER_POOL_ID == null || TABLE_NAME == null || SNS_TOPIC_ARN == null) {
                logger.error("Missing required environment variables.");
                return null;
            }

            Map<String, Object> eventDetail = (Map<String, Object>) event.getDetail();
            if (eventDetail == null || !eventDetail.containsKey("taskId")) {
                logger.error("Missing taskId in event payload.");
                return null;
            }

            String taskId = eventDetail.get("taskId").toString();
            logger.info("Processing reminder for taskId: {}", taskId);

            Map<String, AttributeValue> taskItem = getTask(taskId);
            if (taskItem == null) {
                logger.error("Task not found for taskId: {}", taskId);
                return null;
            }

            String status = taskItem.containsKey("status") ? taskItem.get("status").s() : "unknown";
            if (!ACTIVE_STATUS.equalsIgnoreCase(status)) {
                logger.warn("Task is not active for taskId: {}, status: {}", taskId, status);
                return null;
            }

            String assigneeId = taskItem.containsKey("assigneeId") ? taskItem.get("assigneeId").s() : null;
            String title = taskItem.containsKey("title") ? taskItem.get("title").s() : "Untitled";
            String deadline = taskItem.containsKey("deadline") ? taskItem.get("deadline").s() : null;

            if (assigneeId == null || deadline == null) {
                logger.error("Missing assigneeId or deadline for taskId: {}", taskId);
                return null;
            }

            String email = getUserEmail(assigneeId);
            if (email == null) {
                logger.error("No email found for assigneeId: {}", assigneeId);
                return null;
            }

            sendNotification(email, title, deadline, taskId);

        } catch (Exception e) {
            logger.error("Error processing event: {}", e.getMessage());
        }
        return null;
    }

    private Map<String, AttributeValue> getTask(String taskId) {
        try {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("taskId", AttributeValue.builder().s(taskId).build());

            GetItemRequest request = GetItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(key)
                    .build();

            GetItemResponse response = dynamoDbClient.getItem(request);
            return response.hasItem() ? response.item() : null;

        } catch (Exception e) {
            logger.error("Failed to fetch taskId {}: {}", taskId, e.getMessage());
            return null;
        }
    }

    private String getUserEmail(String assigneeId) {
        try {
            AdminGetUserRequest request = AdminGetUserRequest.builder()
                    .userPoolId(USER_POOL_ID)
                    .username(assigneeId)
                    .build();

            AdminGetUserResponse response = cognitoClient.adminGetUser(request);
            for (AttributeType attribute : response.userAttributes()) {
                if ("email".equals(attribute.name())) {
                    return attribute.value();
                }
            }

            logger.warn("Email attribute not found for assigneeId: {}", assigneeId);
            return null;

        } catch (Exception e) {
            logger.error("Failed to fetch user {}: {}", assigneeId, e.getMessage());
            return null;
        }
    }

    private void sendNotification(String email, String title, String deadline, String taskId) {
        try {
            String message = String.format("Reminder: Task '%s' (ID: %s) is due in 1 hour at %s.", title, taskId, deadline);
            PublishRequest request = PublishRequest.builder()
                    .message(message)
                    .subject("Task Reminder")
                    .topicArn(SNS_TOPIC_ARN)
                    .build();

            snsClient.publish(request);
            logger.info("Notification sent to {} for taskId: {}", email, taskId);

        } catch (Exception e) {
            logger.error("Failed to send notification for taskId {}: {}", taskId, e.getMessage());
        }
    }
}
