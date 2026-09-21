package com.seismic.deliberation.service;

import com.seismic.deliberation.domain.Domain.Branch;
import com.seismic.deliberation.domain.Domain.PickRow;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

final class RowMappers {
    private RowMappers() {}

    static final RowMapper<PickRow> PICK = (rs, i) -> new PickRow(
            rs.getLong("id"), rs.getLong("branch_id"), rs.getLong("station_id"),
            rs.getString("phase"), rs.getLong("epoch_ms"), rs.getString("polarity"),
            nullableLong(rs, "ci_low_ms"), nullableLong(rs, "ci_high_ms"),
            rs.getDouble("confidence"), rs.getString("status"), rs.getString("source"),
            rs.getInt("version"), nullableLong(rs, "merged_into"));

    static final RowMapper<Branch> BRANCH = (rs, i) -> new Branch(
            rs.getLong("id"), rs.getLong("event_id"), rs.getString("name"),
            nullableLong(rs, "parent_id"), rs.getLong("model_version_id"),
            rs.getLong("pick_version"), rs.getInt("frozen") == 1,
            nullableLong(rs, "frozen_station_set"), nullableLong(rs, "frozen_model_version_id"),
            nullableLong(rs, "frozen_pick_version"));

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
