package opinion

import (
	"context"
	"sync"
)

// Store persists the opinion event configurations (module 5 of the
// public-opinion-response training). The prototype ships the in-memory
// implementation; the interface keeps the service layer independent of
// the storage backend. The cascade rule of the database (the event
// vanishes when its run is deleted) is implemented by DeleteByRun, the
// uniform cleanup entry the drills service calls through its
// run-opinion cleaner hook. The releases, the media questions, the
// complaints and the reviews of a run cascade the same way: DeleteByRun
// appends the cleanup of every opinion object kind.
type Store interface {
	UpsertEvent(ctx context.Context, event Event) error
	GetEvent(ctx context.Context, runID string) (Event, error)
	DeleteEvent(ctx context.Context, runID string) error
	DeleteByRun(ctx context.Context, runID string) error
	// Opinion posts (舆情信息): created with POST, listed with filters
	// and pagination, fetched / updated / removed by id. The service
	// validates the input and enforces the warn state machine before
	// the store persists.
	CreatePost(ctx context.Context, post Post) error
	ListPosts(ctx context.Context, runID string, filter PostFilter) ([]Post, int, error)
	GetPost(ctx context.Context, runID, id string) (Post, error)
	UpdatePost(ctx context.Context, post Post) error
	DeletePost(ctx context.Context, runID, id string) error
	// Opinion releases (信息发布记录): created with POST, listed with
	// filters and pagination, fetched / updated / removed by id. The
	// service validates the input and enforces the publication state
	// machine before the store persists.
	CreateRelease(ctx context.Context, release Release) error
	ListReleases(ctx context.Context, runID string, filter ReleaseFilter) ([]Release, int, error)
	GetRelease(ctx context.Context, runID, id string) (Release, error)
	UpdateRelease(ctx context.Context, release Release) error
	DeleteRelease(ctx context.Context, runID, id string) error
	// Opinion media questions (媒体问答记录): created with POST, listed
	// with filters and pagination, fetched / updated / removed by id.
	// The service validates the input and enforces the answer state
	// machine before the store persists.
	CreateMediaQuestion(ctx context.Context, question MediaQuestion) error
	ListMediaQuestions(ctx context.Context, runID string, filter MediaQuestionFilter) ([]MediaQuestion, int, error)
	GetMediaQuestion(ctx context.Context, runID, id string) (MediaQuestion, error)
	UpdateMediaQuestion(ctx context.Context, question MediaQuestion) error
	DeleteMediaQuestion(ctx context.Context, runID, id string) error
	// Opinion complaints (投诉处理记录): created with POST, listed with
	// filters and pagination, fetched / updated / removed by id. The
	// service validates the input and enforces the handling state
	// machine before the store persists.
	CreateComplaint(ctx context.Context, complaint Complaint) error
	ListComplaints(ctx context.Context, runID string, filter ComplaintFilter) ([]Complaint, int, error)
	GetComplaint(ctx context.Context, runID, id string) (Complaint, error)
	UpdateComplaint(ctx context.Context, complaint Complaint) error
	DeleteComplaint(ctx context.Context, runID, id string) error
	// Opinion reviews (舆情复盘记录): upserted with PUT and fetched /
	// removed by run (one review per run, at most one row). The service
	// validates the input and enforces the run write gate before the
	// store persists.
	UpsertReview(ctx context.Context, review Review) error
	GetReview(ctx context.Context, runID string) (Review, error)
	DeleteReview(ctx context.Context, runID string) error
}

// InMemoryStore keeps the opinion event rows in an insertion-ordered
// slice guarded by a mutex. It implements Store for the prototype and
// never touches a database; a database-backed store arrives with a
// later slice. At most one event row exists per run (the service
// upserts by run_id). It also implements the drills.RunOpinionCleaner
// interface (DeleteByRun), so the run-deletion cascade works against
// the real event data.
type InMemoryStore struct {
	mu             sync.Mutex
	events         []Event
	posts          []Post
	releases       []Release
	mediaQuestions []MediaQuestion
	complaints     []Complaint
	reviews        []Review
}

// NewInMemoryStore returns an empty in-memory opinion store.
func NewInMemoryStore() *InMemoryStore {
	return &InMemoryStore{}
}

// UpsertEvent inserts the event or replaces the event with the same
// run_id (its id and created_at are preserved by the service).
func (s *InMemoryStore) UpsertEvent(_ context.Context, event Event) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, item := range s.events {
		if item.RunID == event.RunID {
			s.events[i] = cloneEvent(event)
			return nil
		}
	}
	s.events = append(s.events, cloneEvent(event))
	return nil
}

// GetEvent returns the event of the run, or ErrEventNotFound.
func (s *InMemoryStore) GetEvent(_ context.Context, runID string) (Event, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfEvent(runID)
	if index < 0 {
		return Event{}, ErrEventNotFound
	}
	return cloneEvent(s.events[index]), nil
}

// DeleteEvent removes the event of the run, or ErrEventNotFound.
func (s *InMemoryStore) DeleteEvent(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfEvent(runID)
	if index < 0 {
		return ErrEventNotFound
	}
	s.events = append(s.events[:index], s.events[index+1:]...)
	return nil
}

// DeleteByRun removes every opinion object of the run (the in-memory
// counterpart of the DB's ON DELETE CASCADE; the uniform cleanup entry
// the drills service calls through SetOpinionCleaner). It cleans the
// opinion events, the opinion posts, the opinion releases, the opinion
// media questions, the opinion complaints and the opinion reviews of
// the run. Removing no objects is not an error.
func (s *InMemoryStore) DeleteByRun(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.events[:0]
	for _, item := range s.events {
		if item.RunID != runID {
			kept = append(kept, item)
		}
	}
	s.events = kept
	keptPosts := s.posts[:0]
	for _, item := range s.posts {
		if item.RunID != runID {
			keptPosts = append(keptPosts, item)
		}
	}
	s.posts = keptPosts
	keptReleases := s.releases[:0]
	for _, item := range s.releases {
		if item.RunID != runID {
			keptReleases = append(keptReleases, item)
		}
	}
	s.releases = keptReleases
	keptQuestions := s.mediaQuestions[:0]
	for _, item := range s.mediaQuestions {
		if item.RunID != runID {
			keptQuestions = append(keptQuestions, item)
		}
	}
	s.mediaQuestions = keptQuestions
	keptComplaints := s.complaints[:0]
	for _, item := range s.complaints {
		if item.RunID != runID {
			keptComplaints = append(keptComplaints, item)
		}
	}
	s.complaints = keptComplaints
	keptReviews := s.reviews[:0]
	for _, item := range s.reviews {
		if item.RunID != runID {
			keptReviews = append(keptReviews, item)
		}
	}
	s.reviews = keptReviews
	return nil
}

func (s *InMemoryStore) indexOfEvent(runID string) int {
	for i, item := range s.events {
		if item.RunID == runID {
			return i
		}
	}
	return -1
}

func cloneEvent(event Event) Event {
	cloned := event
	if event.OccurredAt != nil {
		value := *event.OccurredAt
		cloned.OccurredAt = &value
	}
	cloned.Metadata = cloneMap(event.Metadata)
	return cloned
}

func cloneMap(source map[string]any) map[string]any {
	cloned := make(map[string]any, len(source))
	for key, value := range source {
		cloned[key] = value
	}
	return cloned
}
