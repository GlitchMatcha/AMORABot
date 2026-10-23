package com.amore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class EventTimeTest {

    private static long utc(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC).toEpochSecond();
    }

    @Test
    void dashesInTheDateAreNotReadAsAnOffset() {
        // 20:30 PDT (UTC-7) is 03:30 UTC the next day.
        assertEquals(utc(2026, 10, 24, 3, 30), AnnouncementListener.parseEventEpoch("2026-10-23 20:30 PDT"));
        assertEquals(utc(2026, 10, 24, 3, 30), AnnouncementListener.parseEventEpoch("10/23/2026 20:30 PDT"));
        assertEquals(utc(2026, 10, 24, 3, 30), AnnouncementListener.parseEventEpoch("23-10-2026 20:30 PDT"));
    }

    @Test
    void explicitOffsetsAreHonouredWithDashedDates() {
        assertEquals(utc(2026, 10, 23, 13, 30), AnnouncementListener.parseEventEpoch("2026-10-23 20:30 GMT+7"));
        assertEquals(utc(2026, 10, 23, 15, 0), AnnouncementListener.parseEventEpoch("2026-10-23 20:30 UTC+5:30"));
        assertEquals(utc(2026, 10, 23, 15, 0), AnnouncementListener.parseEventEpoch("2026-10-23 20:30 GMT+05:30"));
        assertEquals(utc(2026, 10, 24, 5, 30), AnnouncementListener.parseEventEpoch("2026-10-23 20:30 GMT-9"));
    }

    @Test
    void namedZonesAndNamedMonthDatesStillWork() {
        assertEquals(utc(2026, 10, 25, 1, 30), AnnouncementListener.parseEventEpoch("October 24, 2026 20:30 EST"));
        assertEquals(utc(2026, 10, 24, 12, 30), AnnouncementListener.parseEventEpoch("2026-10-24 8:30 PM PHT"));
        assertEquals(utc(2026, 10, 24, 18, 30), AnnouncementListener.parseEventEpoch("2026-10-24 20:30 CEST"));
    }

    @Test
    void noZoneMeansUtc() {
        assertEquals(utc(2026, 10, 23, 20, 30), AnnouncementListener.parseEventEpoch("2026-10-23 20:30"));
    }
}
