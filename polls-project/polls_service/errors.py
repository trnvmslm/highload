class PollNotFoundError(Exception):
    def __init__(self, poll_id: int):
        super().__init__(f"Poll {poll_id} not found")


class InvalidPollError(Exception):
    pass


class NotPollOwnerError(Exception):
    def __init__(self, poll_id: int, user_id: int):
        super().__init__(f"User {user_id} is not the author of poll {poll_id}")


class PollClientError(Exception):
    pass


class RetryableStatusError(Exception):
    def __init__(self, status_code: int):
        super().__init__(f"Server answered HTTP {status_code}")
