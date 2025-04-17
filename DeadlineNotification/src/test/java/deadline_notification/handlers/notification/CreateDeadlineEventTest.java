package deadline_notification.handlers.notification;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;

import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.CreateScheduleRequest;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CreateDeadlineEventTest {

    private SchedulerClient mockSchedulerClient;
    private CreateDeadlineEvent handler;

    @BeforeEach
    public void setup() {
        mockSchedulerClient = mock(SchedulerClient.class);
        handler = new CreateDeadlineEvent(mockSchedulerClient);
    }

    @Test
    public void testValidInsertEventCreatesSchedule() {
        String taskId = "task-123";
        OffsetDateTime futureTime = OffsetDateTime.now().plusHours(2);

        // Create DynamoDB stream record
        StreamRecord streamRecord = new StreamRecord();
        streamRecord.setNewImage(Map.of(
                "taskId", AttributeValue.builder().s(taskId).build(),
                "deadline", AttributeValue.builder().s(futureTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).build(),
                "title", AttributeValue.builder().s("Test Title").build()
        ));

        DynamodbEvent.DynamodbStreamRecord record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventName("INSERT");
        record.setDynamodb(streamRecord);

        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(record));

        handler.handleRequest(event, mock(Context.class));

        ArgumentCaptor<CreateScheduleRequest> captor = ArgumentCaptor.forClass(CreateScheduleRequest.class);
        verify(mockSchedulerClient, times(1)).createSchedule(captor.capture());

        CreateScheduleRequest request = captor.getValue();
        assertEquals("TaskReminder_" + taskId, request.name());
    }

    @Test
    public void testReminderTimeInPastIsSkipped() {
        String taskId = "past-task";
        OffsetDateTime pastTime = OffsetDateTime.now().minusHours(2);

        // Use SDK v1 AttributeValue and StreamRecord
        Map<String, AttributeValue> newImage = Map.of(
                "taskId", new AttributeValue().withS(taskId),
                "deadline", new AttributeValue().withS(pastTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        );

        StreamRecord streamRecord = new StreamRecord();
        streamRecord.setNewImage(newImage);

        DynamodbEvent.DynamodbStreamRecord record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventName("INSERT");
        record.setDynamodb(streamRecord);

        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(record));

        handler.handleRequest(event, mock(Context.class));

        verify(mockSchedulerClient, never()).createSchedule((CreateScheduleRequest) any());
    }


    @Test
    public void testMissingTaskIdSkipsRecord() {
        OffsetDateTime futureTime = OffsetDateTime.now().plusHours(3);

        Map<String, AttributeValue> newImage = Map.of(
                "deadline", new AttributeValue().withS(futureTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        );

        StreamRecord streamRecord = new StreamRecord();
        streamRecord.setNewImage(newImage);

        DynamodbEvent.DynamodbStreamRecord record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventName("INSERT");
        record.setDynamodb(streamRecord);

        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(record));

        handler.handleRequest(event, mock(Context.class));

        // Verify that no schedule was attempted due to missing taskId
        verify(mockSchedulerClient, never()).createSchedule((CreateScheduleRequest) any());
    }


    @Test
    public void testInvalidDateFormatSkipsRecord() {
        DynamodbEvent.DynamodbStreamRecord record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventName("INSERT");
        record.setDynamodb(new DynamodbEvent.DynamodbStreamRecord().getDynamodb());
        record.getDynamodb().setNewImage(Map.of(
                "taskId", new AttributeValue().withS("task-789"),
                "deadline", new AttributeValue().withS("not-a-date") // Invalid date format
        ));

        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(record));

        handler.handleRequest(event, mock(Context.class));

        // Verify that no schedule was created due to the invalid date format
        verify(mockSchedulerClient, never()).createSchedule((CreateScheduleRequest) any());
    }

    @Test
    public void testModifyEventAlsoCreatesSchedule() {
        String taskId = "task-modify";
        OffsetDateTime futureTime = OffsetDateTime.now().plusHours(1);

        // Create MODIFY event record
        DynamodbEvent.DynamodbStreamRecord record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventName("MODIFY");
        record.setDynamodb(new DynamodbEvent.DynamodbStreamRecord().getDynamodb());
        record.getDynamodb().setNewImage(Map.of(
                "taskId", new AttributeValue().withS(taskId),
                "deadline", new AttributeValue().withS(futureTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        ));

        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(record));

        // Trigger the handler with the MODIFY event
        handler.handleRequest(event, mock(Context.class));

        // Verify that the createSchedule method was called once for the MODIFY event
        verify(mockSchedulerClient, times(1)).createSchedule(any());
    }
}
