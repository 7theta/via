(ns via.transit.time
  (:require [cognitect.transit :as transit]
            [time-literals.read-write]
            #?(:cljs
               [java.time :refer [Duration Period
                                  LocalDate LocalTime LocalDateTime ZonedDateTime
                                  OffsetTime OffsetDateTime Instant ZoneId
                                  DayOfWeek Month YearMonth Year]]))
  #?(:clj
     (:import [java.io ByteArrayOutputStream ByteArrayInputStream]
              [java.time Duration Period
               LocalDate LocalTime LocalDateTime ZonedDateTime
               OffsetTime OffsetDateTime Instant ZoneId
               DayOfWeek Month YearMonth Year])))

(def ^:private time-classes
  {'duration Duration
   'period Period
   'date LocalDate
   'time LocalTime
   'date-time LocalDateTime
   'zoned-date-time ZonedDateTime
   'offset-time OffsetTime
   'offset-date-time OffsetDateTime
   'instant Instant
   'zone ZoneId
   'day-of-week DayOfWeek
   'month Month
   'year-month YearMonth
   'year Year})

(def write
  (into {}
        (for [[tick-class host-class] time-classes]
          [host-class (transit/write-handler (constantly (name tick-class)) str)])))

(def read
  (into {} (for [[sym fun] time-literals.read-write/tags]
             [(name sym) (transit/read-handler fun)])))
