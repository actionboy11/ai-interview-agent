CREATE TABLE users (
  id uuid PRIMARY KEY,
  username varchar(100) NOT NULL,
  normalized_username varchar(100) NOT NULL UNIQUE,
  password_hash varchar(255) NOT NULL,
  display_name varchar(100) NOT NULL,
  role varchar(20) NOT NULL CHECK (role IN ('ADMIN', 'USER')),
  status varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
  must_change_password boolean NOT NULL,
  created_at timestamp(6) WITH TIME ZONE NOT NULL,
  updated_at timestamp(6) WITH TIME ZONE NOT NULL,
  last_login_at timestamp(6) WITH TIME ZONE,
  version bigint NOT NULL
);
CREATE INDEX idx_user_status ON users (status);

CREATE TABLE registration_invites (
  id uuid PRIMARY KEY,
  invite_hash varchar(64) NOT NULL UNIQUE,
  status varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'USED', 'REVOKED', 'EXPIRED')),
  expires_at timestamp(6) WITH TIME ZONE NOT NULL,
  created_by uuid NOT NULL REFERENCES users (id),
  used_by uuid REFERENCES users (id),
  used_at timestamp(6) WITH TIME ZONE,
  created_at timestamp(6) WITH TIME ZONE NOT NULL,
  updated_at timestamp(6) WITH TIME ZONE NOT NULL,
  version bigint NOT NULL
);
CREATE INDEX idx_registration_invite_status ON registration_invites (status);
CREATE INDEX idx_registration_invite_expiry ON registration_invites (expires_at);

-- UNIQUE constraints supply indexes for normalized_username and invite_hash.
