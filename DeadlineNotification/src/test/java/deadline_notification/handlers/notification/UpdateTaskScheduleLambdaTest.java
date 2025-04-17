package deadline_notification.handlers.notification;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.*;
import software.amazon.awssdk.services.scheduler.model.ResourceNotFoundException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpdateTaskScheduleLambdaTest {

    @InjectMocks
    private UpdateTaskScheduleLambda lambda;

    @Mock
    private SchedulerClient mockSchedulerClient;

    @Mock
    private Context mockContext;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        System.setProperty("TARGET_LAMBDA_ARN", "arn:aws:lambda:region:account:function:targetFunction");
        System.setProperty("SCHEDULER_ROLE_ARN", "arn:aws:iam::account:role/schedulerRole");
    }

    @Test
    void testHandleRequest_withModifyEvent() {
        // Prepare mock event
        DynamodbStreamRecord record = mock(DynamodbStreamRecord.class);
        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(record));

        // Mock DynamoDB event data
        Map<String, AttributeValue> newImage = Map.of(
                "taskId", AttributeValue.builder().s("task1").build(),
                "status", AttributeValue.builder().s("active").build(),
                "deadline", AttributeValue.builder().s("2025-05-01T10:00:00+00:00").build(),
                "assigneeId", AttributeValue.builder().s("user1").build()
        );

        Map<String, AttributeValue> oldImage = Map.of(
                "taskId", AttributeValue.builder().s("task1").build(),
                "status", AttributeValue.builder().s("inactive").build(),
                "deadline", AttributeValue.builder().s("2025-05-01T09:00:00+00:00").build(),
                "assigneeId", AttributeValue.builder().s("user1").build()
        );

        when(record.getEventName()).thenReturn("MODIFY");
        when(record.getDynamodb().getNewImage()).thenReturn(newImage);
        when(record.getDynamodb().getOldImage()).thenReturn(oldImage);

        // Call the method
        lambda.handleRequest(event, mockContext);

        // Verify interactions
        verify(mockSchedulerClient, times(1)).deleteSchedule(any(DeleteScheduleRequest.class));
        verify(mockSchedulerClient, times(1)).createSchedule(any(CreateScheduleRequest.class));
    }

    @Test
    void testHandleRequest_withMissingTaskId() {
        // Prepare mock event
        DynamodbStreamRecord record = mock(DynamodbStreamRecord.class);
        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(record));

        // Mock DynamoDB event data with missing taskId
        Map<String, AttributeValue> newImage = Map.of(
                "status", AttributeValue.builder().s("active").build(),
                "deadline", AttributeValue.builder().s("2025-05-01T10:00:00+00:00").build()
        );

        when(record.getEventName()).thenReturn("MODIFY");
        when(record.getDynamodb().getNewImage()).thenReturn(newImage);

        // Call the method and assert that it does not crash
        lambda.handleRequest(event, mockContext);
    }

    @Test
    void testHandleRequest_withInvalidDeadlineFormat() {
        // Prepare mock event with invalid deadline format
        DynamodbStreamRecord record = mock(DynamodbStreamRecord.class);
        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(record));

        Map<String, AttributeValue> newImage = Map.of(
                "taskId", AttributeValue.builder().s("task2").build(),
                "status", AttributeValue.builder().s("active").build(),
                "deadline", AttributeValue.builder().s("invalid-deadline").build(),
                "assigneeId", AttributeValue.builder().s("user2").build()
        );

        when(record.getEventName()).thenReturn("MODIFY");
        when(record.getDynamodb().getNewImage()).thenReturn(newImage);

        // Call the method and expect the error log for invalid deadline format
        lambda.handleRequest(event, mockContext);
    }

    @Test
    void testDeleteSchedule_withResourceNotFound() {
        // Mocking scheduler client to throw ResourceNotFoundException
        doThrow(ResourceNotFoundException.class).when(mockSchedulerClient).deleteSchedule(any(DeleteScheduleRequest.class));

        // Call deleteSchedule and assert it handles gracefully
        lambda.deleteSchedule("task3");

        // Verify that the exception is caught and handled without crashing
        verify(mockSchedulerClient, times(1)).deleteSchedule(any(DeleteScheduleRequest.class));
    }

    @Test
    void testCreateSchedule_withPastReminderTime() {
        // Test creating a schedule with a reminder time in the past
        OffsetDateTime pastReminderTime = OffsetDateTime.now().minusDays(1);
        Map<String, AttributeValue> taskItem = Map.of(
                "taskId", AttributeValue.builder().s("task4").build(),
                "status", AttributeValue.builder().s("active").build(),
                "deadline", AttributeValue.builder().s("2025-05-01T10:00:00+00:00").build(),
                "assigneeId", AttributeValue.builder().s("user3").build()
        );

        // Call the method and expect the past reminder time to be handled gracefully
        lambda.createSchedule("task4", pastReminderTime, taskItem);

        // Verify interactions
        verify(mockSchedulerClient, times(0)).createSchedule(any(CreateScheduleRequest.class));
    }

    @Test
    void testParseDeadline_invalidFormat() {
        // Test that the invalid deadline format is handled properly
        OffsetDateTime result = lambda.parseDeadline("invalid-deadline", "task5");
        assertNull(result, "Expected null due to invalid date format");
    }

    @Test
    void testParseDeadline_validFormat() {
        // Test that a valid deadline is parsed correctly
        String validDeadline = "2025-05-01T10:00:00+00:00";
        OffsetDateTime result = lambda.parseDeadline(validDeadline, "task6");
        assertNotNull(result, "Expected non-null result for valid date format");
        assertEquals(validDeadline, result.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }
}
