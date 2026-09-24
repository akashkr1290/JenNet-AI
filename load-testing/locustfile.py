"""
Gap-backlog Patch 54 (Sep 2026 audit): load testing for JanNet AI's
backend, using Locust (https://locust.io/) - a real, runnable script
against a live deployment, not just a plan document.

Honest scope: this defines realistic user behavior (register/login once,
then repeatedly submit complaints and check status, weighted toward
reads over writes like real usage) and can genuinely be run - but it
has NOT been run in this sandbox, because there is no live JanNet AI
backend + MySQL + AI service deployment reachable here to run it
against (the same "no live AWS/database" constraint every deployment-
related file in this project already documents). This is the tool
ready for someone with a real staging/pilot deployment to point at it,
not a report of results that don't exist.

Usage (against a real running deployment):
    pip install locust
    locust -f locustfile.py --host=https://your-jannet-deployment.example.com

Then open http://localhost:8089 and set the user count/spawn rate for
100/500/1000 users per Gap-backlog Patch 54's own scenarios.
"""
from __future__ import annotations

import random
import uuid
from pathlib import Path

from locust import HttpUser, between, task


class CitizenUser(HttpUser):
    """Simulates a citizen: logs in once, then mostly checks their
    complaint list/detail, occasionally submits a new one - realistic
    read-heavy civic-app usage, not a pure write-hammer."""

    wait_time = between(2, 8)  # seconds between actions - a human tapping around, not a bot

    def on_start(self) -> None:
        # A real load test needs real seeded test accounts (this
        # environment's OTP-gated registration can't be scripted through
        # without a real SMS/OTP backend) - TEST_MOBILE_NUMBERS should be
        # pre-provisioned, verified citizen accounts on the target
        # environment. Falls back to an obviously-fake credential so a
        # misconfigured run fails fast and loud (401s) rather than
        # silently testing nothing.
        mobile = random.choice(TEST_MOBILE_NUMBERS) if TEST_MOBILE_NUMBERS else "0000000000"
        response = self.client.post("/api/v1/auth/login", json={
            "mobileNumber": mobile,
            "password": TEST_PASSWORD,
        }, name="/auth/login")
        self.token = response.json().get("accessToken") if response.status_code == 200 else None

    def _auth_headers(self) -> dict:
        return {"Authorization": f"Bearer {self.token}"} if self.token else {}

    @task(5)
    def list_my_complaints(self) -> None:
        self.client.get("/api/v1/complaints", headers=self._auth_headers(), name="/complaints (list)")

    @task(3)
    def view_complaint_detail(self) -> None:
        # A real run should seed this with real complaint IDs for the
        # test account - 1 is a placeholder that will 404 against a
        # fresh environment, which is still useful signal (404 latency
        # is real latency) but not the intended "view a real complaint"
        # scenario.
        self.client.get("/api/v1/complaints/1", headers=self._auth_headers(), name="/complaints/:id (detail)")

    @task(1)
    def submit_complaint(self) -> None:
        # A real, valid 640x640 JPEG (fixtures/test_photo.jpg, generated
        # this audit) - large enough to clear ai-service's
        # min_image_width_px/min_image_height_px quality gate (SRS 21.3,
        # default 320x320), so this exercises the real happy path, not
        # the 422 UNPROCESSABLE_IMAGE path.
        files = {"photo": ("test.jpg", TEST_PHOTO_BYTES, "image/jpeg")}
        data = {
            "description": f"Load test complaint {uuid.uuid4()}",
            "latitude": "12.9716",
            "longitude": "77.5946",
            "locationSource": "DEVICE_GPS",
        }
        self.client.post("/api/v1/complaints", files=files, data=data,
                          headers=self._auth_headers(), name="/complaints (submit)")


# --- Configuration - fill these in for a real target environment ---
TEST_MOBILE_NUMBERS: list[str] = []  # e.g. ["9000000001", "9000000002", ...] - pre-verified test accounts
TEST_PASSWORD = "REPLACE_ME"
TEST_PHOTO_BYTES = (Path(__file__).parent / "fixtures" / "test_photo.jpg").read_bytes()
