import json
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from polls_service.errors import InvalidPollError, NotPollOwnerError, PollNotFoundError
from polls_service.fault import FaultInjector
from polls_service.models import CreatePollCommand, PollDetails, PollResults, VoteOutcome
from polls_service.service import PollService

IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

VOTE_RESPONSES = {
    VoteOutcome.ACCEPTED: (HTTPStatus.CREATED, {"status": "ACCEPTED"}),
    VoteOutcome.ALREADY_COUNTED: (HTTPStatus.OK, {"status": "ALREADY_COUNTED"}),
    VoteOutcome.CONFLICT: (HTTPStatus.CONFLICT, {"error": "User already voted for another option"}),
    VoteOutcome.POLL_CLOSED: (HTTPStatus.GONE, {"error": "Poll is closed"}),
    VoteOutcome.INVALID_OPTION: (HTTPStatus.BAD_REQUEST, {"error": "Unknown option"}),
}


def to_json_bytes(body: dict) -> bytes:
    return json.dumps(body, separators=(",", ":")).encode()


def results_to_json(results: PollResults) -> dict:
    return {
        "pollId": results.poll_id,
        "totalVotes": results.total_votes,
        "options": [
            {"optionId": option.option_id, "text": option.text, "votes": option.votes, "percent": option.percent}
            for option in results.options
        ],
    }


def poll_to_json(details: PollDetails) -> dict:
    poll = details.poll
    return {
        "pollId": poll.id,
        "ownerId": poll.owner_id,
        "question": poll.question,
        "options": list(poll.options),
        "endsAtEpochMs": poll.ends_at_ms,
        "open": details.is_open,
        "myOptionId": details.my_option_id,
    }


class PollHttpServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, poll_service: PollService, fault_injector: FaultInjector):
        super().__init__(address, PollRequestHandler)
        self.poll_service = poll_service
        self.fault_injector = fault_injector


class PollRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    disable_nagle_algorithm = True

    def do_GET(self) -> None:
        self._handle("GET")

    def do_POST(self) -> None:
        self._handle("POST")

    def log_message(self, format, *args) -> None:
        pass

    def _handle(self, method: str) -> None:
        body = self._read_body()
        if self.server.fault_injector.should_fail():
            self._send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "Injected fault"})
            return
        try:
            self._route(method, body)
        except PollNotFoundError as error:
            self._send_json(HTTPStatus.NOT_FOUND, {"error": str(error)})
        except NotPollOwnerError as error:
            self._send_json(HTTPStatus.FORBIDDEN, {"error": str(error)})
        except (InvalidPollError, ValueError, KeyError, TypeError) as error:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": str(error)})

    def _route(self, method: str, body: bytes) -> None:
        url = urlsplit(self.path)
        segments = url.path.strip("/").split("/")
        if method == "POST" and segments == ["v1", "polls"]:
            self._create_poll(json.loads(body))
        elif method == "GET" and len(segments) == 3:
            self._get_poll(int(segments[2]), _user_id_from_query(url.query))
        elif method == "POST" and len(segments) == 4 and segments[3] == "votes":
            self._cast_vote(int(segments[2]), json.loads(body))
        elif method == "GET" and len(segments) == 4 and segments[3] == "results":
            self._get_results(int(segments[2]))
        elif method == "POST" and len(segments) == 4 and segments[3] == "close":
            self._close_poll(int(segments[2]), json.loads(body))
        else:
            self._send_json(HTTPStatus.METHOD_NOT_ALLOWED, {"error": "Unsupported route"})

    def _create_poll(self, body: dict) -> None:
        command = CreatePollCommand(body["ownerId"], body["question"], tuple(body["options"]), body["endsAtEpochMs"])
        idempotency_key = self.headers.get(IDEMPOTENCY_KEY_HEADER, "")
        poll_id = self.server.poll_service.create_poll(idempotency_key, command)
        self._send_json(HTTPStatus.CREATED, {"pollId": poll_id})

    def _cast_vote(self, poll_id: int, body: dict) -> None:
        outcome = self.server.poll_service.cast_vote(poll_id, body["userId"], body["optionId"])
        status, reply = VOTE_RESPONSES[outcome]
        self._send_json(status, reply)

    def _get_results(self, poll_id: int) -> None:
        results = self.server.poll_service.get_results(poll_id)
        self._send_json(HTTPStatus.OK, results_to_json(results))

    def _get_poll(self, poll_id: int, user_id: int) -> None:
        details = self.server.poll_service.get_poll(poll_id, user_id)
        self._send_json(HTTPStatus.OK, poll_to_json(details))

    def _close_poll(self, poll_id: int, body: dict) -> None:
        poll = self.server.poll_service.close_poll(poll_id, body["userId"])
        self._send_json(HTTPStatus.OK, {"endsAtEpochMs": poll.ends_at_ms})

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length", 0))
        return self.rfile.read(length)

    def _send_json(self, status: HTTPStatus, body: dict) -> None:
        payload = to_json_bytes(body)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def _user_id_from_query(query: str) -> int:
    values = parse_qs(query).get("userId", ["0"])
    return int(values[0])


class RestServer:
    def __init__(self, port: int, poll_service: PollService, fault_injector: FaultInjector):
        self._server = PollHttpServer(("", port), poll_service, fault_injector)
        self.port = self._server.server_address[1]
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()

    def __enter__(self) -> "RestServer":
        self.start()
        return self

    def __exit__(self, *exc_info) -> None:
        self.stop()
