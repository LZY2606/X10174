package com.phase.room.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PickEventRepo {
    private final JdbcTemplate jdbc;

    public PickEventRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Rows.PickEventRow> MAPPER = (rs, n) -> {
        long undo = rs.getLong("undo_of");
        return new Rows.PickEventRow(rs.getLong("id"), rs.getLong("event_id"),
                rs.getLong("station_id"), rs.getInt("seq"), rs.getString("type"),
                rs.getString("payload_json"), rs.getString("inverse_json"),
                rs.wasNull() ? null : undo);
    };

    public int nextSeq(long eventId, long stationId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq),0) FROM pick_event WHERE event_id=? AND station_id=?",
                Integer.class, eventId, stationId);
        return max + 1;
    }

    public void append(long eventId, long stationId, int seq, String type, String payloadJson,
                       String inverseJson, Long undoOf, double now) {
        jdbc.update("INSERT INTO pick_event(event_id, station_id, seq, type, payload_json,"
                + " inverse_json, undo_of, created_at) VALUES(?,?,?,?,?,?,?,?)",
                eventId, stationId, seq, type, payloadJson, inverseJson, undoOf, now);
    }

    public List<Rows.PickEventRow> findStream(long eventId, long stationId) {
        return jdbc.query("SELECT * FROM pick_event WHERE event_id=? AND station_id=? ORDER BY seq",
                MAPPER, eventId, stationId);
    }

    public List<Rows.PickEventRow> findForEvent(long eventId) {
        return jdbc.query("SELECT * FROM pick_event WHERE event_id=? ORDER BY station_id, seq",
                MAPPER, eventId);
    }

    public List<long[]> eventStationKeys() {
        return jdbc.query("SELECT DISTINCT event_id, station_id FROM pick_event"
                        + " ORDER BY event_id, station_id",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)});
    }
}
