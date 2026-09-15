package quest.server.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TeacherRepository extends JpaRepository<Entities.TeacherEntity, String> {}
