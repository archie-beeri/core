package org.hilel14.archie.beeri.core.users;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;

import java.security.Key;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import org.apache.commons.codec.digest.DigestUtils;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Map;
import org.hilel14.archie.beeri.core.Config;

/**
 *
 * @author hilel14
 */
public class UserManager {
    
    static final Logger LOGGER = LoggerFactory.getLogger(UserManager.class);
    private final String getAllUsers = "SELECT * FROM users";
    private final String getUser = "SELECT * FROM users WHERE username = ?";
    private final String getRole = "SELECT * FROM users WHERE username = ? AND password = ?";
    private final String createUser = "INSERT INTO users (username, password, fullname, rolename) VALUES (?,?,?,?) ";
    private final String updatePassword = "UPDATE users SET password = ? WHERE username = ?";
    private final String updateRole = "UPDATE users SET rolename = ? WHERE username = ?";
    private final String deleteUser = "DELETE FROM users WHERE username = ?";
    private final DataSource dataSource;
    final Key key;
    final GoogleIdTokenVerifier googleIdTokenVerifier;
    
    public UserManager(Config config, Key key) {
        this.dataSource = config.getDataSource();
        this.key = key;
        this.googleIdTokenVerifier
                = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new JacksonFactory()).
                        setAudience(Collections.singletonList(config.getGoogleClientId()))
                        .build();
    }
    
    public List<User> getAllUsers() throws SQLException {
        List<User> users = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();) {
            PreparedStatement statement = connection.prepareStatement(getAllUsers);
            try (ResultSet rs = statement.executeQuery();) {
                while (rs.next()) {
                    User user = new User();
                    user.setId(rs.getLong("id"));
                    user.setUsername(rs.getString("username"));
                    user.setFullname(rs.getString("fullname"));
                    user.setRole(rs.getString("rolename"));
                    users.add(user);
                }
            }
        }
        return users;
    }
    
    public User createUser(User user) throws SQLException {
        try (Connection connection = dataSource.getConnection();) {
            PreparedStatement statement = connection.prepareStatement(createUser);
            statement.setString(1, user.getUsername());
            statement.setString(2, DigestUtils.sha512Hex(user.getPassword()));
            statement.setString(3, user.getFullname());
            statement.setString(4, user.getRole());
            statement.executeUpdate();
        }
        return user;
    }
    
    public void deleteUser(User user) throws SQLException {
        try (Connection connection = dataSource.getConnection();) {
            PreparedStatement statement = connection.prepareStatement(deleteUser);
            statement.setString(1, user.getUsername());
            statement.executeUpdate();
        }
    }
    
    public void updatePassword(User user) throws SQLException {
        try (Connection connection = dataSource.getConnection();) {
            PreparedStatement statement = connection.prepareStatement(updatePassword);
            statement.setString(1, DigestUtils.sha512Hex(user.getPassword()));
            statement.setString(2, user.getUsername());
            statement.executeUpdate();
        }
    }
    
    public void updateRole(User user) throws SQLException {
        try (Connection connection = dataSource.getConnection();) {
            PreparedStatement statement = connection.prepareStatement(updateRole);
            statement.setString(1, user.getRole());
            statement.setString(2, user.getUsername());
            statement.executeUpdate();
        }
    }
    
    public User authenticateWithGoogle(Map<String, Object> socialUser)
            throws GeneralSecurityException, IOException, SQLException {
        String idTokenString = socialUser.get("idToken").toString();
        GoogleIdToken idToken = googleIdTokenVerifier.verify(idTokenString);
        if (idToken == null) {
            LOGGER.warn("Verification failed for user {} ", socialUser.get("email"));
            return null;
        } else {
            Payload payload = idToken.getPayload();
            User user = new User();
            user.setUsername(payload.getEmail());
            user.setRole("user");
            try (Connection connection = dataSource.getConnection();) {
                PreparedStatement statement = connection.prepareStatement(getUser);
                statement.setString(1, user.getUsername());
                try (ResultSet rs = statement.executeQuery();) {
                    if (rs.next()) {
                        user.setRole(rs.getString("rolename"));
                        LOGGER.info("User {} authenticated as {}", user.getUsername(), user.getRole());
                    } else {
                        LOGGER.info("User {} authenticated but not found in database", user.getUsername());
                    }
                }
            }
            user.setFullname(payload.get("name").toString());
            String token = Jwts.builder().setExpiration(new Date(System.currentTimeMillis() + 604800000))
                    .setSubject(user.getRole()).signWith(SignatureAlgorithm.HS512, key).compact();
            user.setToken(token);
            return user;
        }
    }
    
    public User authenticate(Credentials credentials) throws SQLException {
        User user = null;
        try (Connection connection = dataSource.getConnection();) {
            PreparedStatement statement = connection.prepareStatement(getRole);
            statement.setString(1, credentials.getUsername());
            statement.setString(2, DigestUtils.sha512Hex(credentials.getPassword()));
            try (ResultSet rs = statement.executeQuery();) {
                if (rs.next()) {
                    String role = rs.getString("rolename");
                    String token = Jwts.builder().setExpiration(new Date(System.currentTimeMillis() + 604800000))
                            .setSubject(role).signWith(SignatureAlgorithm.HS512, key).compact();
                    user = new User();
                    user.setId(rs.getLong("id"));
                    user.setUsername(rs.getString("username"));
                    user.setFullname(rs.getString("fullname"));
                    user.setRole(role);
                    user.setToken(token);
                }
            }
        }
        return user;
    }
    
}
