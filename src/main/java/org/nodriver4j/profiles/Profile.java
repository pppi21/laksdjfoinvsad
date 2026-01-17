package org.nodriver4j.profiles;

import org.nodriver4j.core.BrowserManager;

public class Profile {
    private String profileID;
    private String email;
    private String password;
    private String firstName;
    private String lastName;
    private String phone;

    private Profile(Builder builder) {
        this.profileID = builder.profileID;
        this.email = builder.email;
        this.password = builder.password;
        this.firstName = builder.firstName;
        this.lastName = builder.lastName;
        this.phone = builder.phone;

    }

    public static Profile.Builder builder() {
        return new Profile.Builder();
    }


    public String profileID() {
        return profileID;
    }

    public String email() {
        return email;
    }

    public String password() {
        return password;
    }

    public String firstName() {
        return firstName;
    }

    public String lastName() {
        return lastName;
    }

    public String phone() {
        return phone;
    }


    public static class Builder {
        private String profileID = java.util.UUID.randomUUID().toString();
        private String email;
        private String password;
        private String firstName;
        private String lastName;
        private String phone;

        private Builder() {}

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder firstName(String firstName) {
            this.firstName = firstName;
            return this;
        }

        public Builder lastName(String lastName) {
            this.lastName = lastName;
            return this;
        }

        public Builder phone(String phone) {
            this.phone = phone;
            return this;
        }

        public Profile build() {
            if (email == null || email.isBlank()) {
                throw new IllegalStateException("email is required");
            }
            return new Profile(this);
        }

    }
}
