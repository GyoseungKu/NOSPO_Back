package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.*;

import java.sql.*;
import java.util.UUID;

class KakaoMigrationTests {
    Connection version4() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        for (String file : new String[]{"V1__email_signup.sql", "V2__member_profile_image.sql",
                "V3__social_identity.sql", "V4__kakao_oauth.sql"})
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/" + file)).populate(connection);
        return connection;
    }
    void version5(Connection connection) throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V5__kakao_reauth_and_link.sql")).populate(connection);
    }
    UUID flow(Connection connection) throws Exception {
        UUID flow = UUID.randomUUID();
        try (var statement = connection.prepareStatement("INSERT INTO oauth_flow(flow_id,state_hash,cookie_hash,return_path,"
                + "created_at,expires_at) VALUES (?,?,?,'/',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
            statement.setBytes(1, id(flow)); statement.setBytes(2, new byte[32]); statement.setBytes(3, new byte[32]);
            statement.executeUpdate();
        }
        return flow;
    }
    void context(Connection connection, UUID flow, int digest) throws Exception {
        UUID proof = UUID.randomUUID(); byte[] hash = new byte[32]; hash[0] = (byte) digest;
        try (var statement = connection.prepareStatement("INSERT INTO auth_proof(proof_id,purpose,proof_hash,created_at,expires_at)"
                + " VALUES (?,'KAKAO_SIGNUP',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
            statement.setBytes(1, id(proof)); statement.setBytes(2, hash); statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("INSERT INTO social_signup_context(proof_id,flow_id,payload_ciphertext)"
                + " VALUES (?,?,?)")) {
            statement.setBytes(1, id(proof)); statement.setBytes(2, id(flow)); statement.setBytes(3, new byte[]{1});
            statement.executeUpdate();
        }
    }
    @Test
    void upgradesExistingLoginFlowAndContextWithoutLosingData() throws Exception {
        try (var connection = version4()) {
            UUID flow = flow(connection); context(connection, flow, 1); version5(connection);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT flow_type,purpose,reauth_proof_id FROM oauth_flow")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("LOGIN");
                assertThat(rows.getString(2)).isNull(); assertThat(rows.getBytes(3)).isNull();
            }
            assertThatThrownBy(() -> context(connection, flow, 2)).isInstanceOf(SQLException.class);
        }
    }
    @Test
    void duplicateContextsFailBeforeFlowAlterationWithoutDeletingRows() throws Exception {
        try (var connection = version4()) {
            UUID flow = flow(connection); context(connection, flow, 1); context(connection, flow, 2);
            assertThatThrownBy(() -> version5(connection)).isInstanceOf(ScriptStatementFailedException.class);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM social_signup_context")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(2);
            }
            assertThatThrownBy(() -> connection.createStatement().executeQuery("SELECT flow_type FROM oauth_flow"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
