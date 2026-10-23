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
    void easternEuropeanZonesStillWork() {
        assertEquals(utc(2026, 10, 23, 17, 30), AnnouncementListener.parseEventEpoch("2026-10-23 20:30 EEST"));
        assertEquals(utc(2026, 12, 23, 18, 30), AnnouncementListener.parseEventEpoch("2026-12-23 20:30 EET"));
    }

    @Test
    void genericUsZonesFollowDaylightSaving() {
        assertEquals(utc(2026, 10, 24, 3, 30), AnnouncementListener.parseEventEpoch("2026-10-23 20:30 PT")); // PDT, -7
        assertEquals(utc(2026, 12, 24, 4, 30), AnnouncementListener.parseEventEpoch("2026-12-23 20:30 PT")); // PST, -8
        assertEquals(utc(2026, 10, 24, 0, 30), AnnouncementListener.parseEventEpoch("2026-10-23 20:30 ET")); // EDT, -4
        assertEquals(utc(2026, 12, 24, 1, 30), AnnouncementListener.parseEventEpoch("2026-12-23 20:30 ET")); // EST, -5
        assertEquals(utc(2026, 10, 24, 1, 30), AnnouncementListener.parseEventEpoch("2026-10-23 20:30 CT")); // CDT, -5
        assertEquals(utc(2026, 10, 24, 2, 30), AnnouncementListener.parseEventEpoch("2026-10-23 20:30 MT")); // MDT, -6
    }

    @Test
    void explicitUsAbbreviationsStayFixed() {
        assertEquals(utc(2026, 7, 24, 3, 30), AnnouncementListener.parseEventEpoch("2026-07-23 20:30 PDT")); // -7
        assertEquals(utc(2026, 7, 24, 4, 30), AnnouncementListener.parseEventEpoch("2026-07-23 20:30 PST")); // fixed -8, as before
    }

    @Test
    void regionNamesAreAcceptedInAnyCase() {
        assertEquals(utc(2026, 10, 24, 3, 30), AnnouncementListener.parseEventEpoch("2026-10-23 20:30 America/Los_Angeles"));
        assertEquals(utc(2026, 10, 24, 3, 30), AnnouncementListener.parseEventEpoch("2026-10-23 20:30 america/los_angeles"));
        assertEquals(utc(2026, 10, 23, 18, 30), AnnouncementListener.parseEventEpoch("2026-10-23 20:30 Europe/Berlin")); // CEST
        assertEquals(utc(2026, 10, 23, 12, 30), AnnouncementListener.parseEventEpoch("2026-10-23 20:30 Asia/Manila"));
        assertEquals(utc(2026, 10, 23, 12, 30), AnnouncementListener.parseEventEpoch("23-10-2026 8:30 PM Asia/Manila"));
    }

    @Test
    void noZoneMeansUtc() {
        assertEquals(utc(2026, 10, 23, 20, 30), AnnouncementListener.parseEventEpoch("2026-10-23 20:30"));
    }
}
