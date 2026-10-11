-- ============================================================================
-- V38: My profile. Every signed-in user keeps their own name, email, phone, job title, photo and password
-- (/profile, open to any signed-in user like signing out: no page or permission to seed).
-- users.photo_url (V1) holds the photo's storage key (avatars/{user}/{uuid}.jpg), served by /profile/photo.
-- ============================================================================

ALTER TABLE users ADD COLUMN job_title VARCHAR(100);
