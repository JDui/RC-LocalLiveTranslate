#include "rc-sensevoice-language-scores.h"

#include <cassert>
#include <limits>

int main() {
  using rc_localtranslate::ReadSenseVoiceLanguageScores;
  const std::array<int32_t, 5> ids = {2, 4, 6, 8, 10};
  float frame[12] = {-12, -12, -3, -12, -2, -12, -1, -12, -4, -12, -5, -12};
  std::array<float, 5> scores;
  assert(ReadSenseVoiceLanguageScores(frame, 12, ids, &scores));
  assert((scores == std::array<float, 5>{-3, -2, -1, -4, -5}));
  frame[0] = 0;  // Blank or text wins: these are not usable language evidence.
  assert(!ReadSenseVoiceLanguageScores(frame, 12, ids, &scores));
  frame[0] = -12;
  frame[7] = std::numeric_limits<float>::quiet_NaN();
  assert(!ReadSenseVoiceLanguageScores(frame, 12, ids, &scores));
  frame[7] = std::numeric_limits<float>::infinity();
  assert(!ReadSenseVoiceLanguageScores(frame, 12, ids, &scores));
  frame[7] = -12;
  auto invalid = ids;
  invalid[0] = -1;
  assert(!ReadSenseVoiceLanguageScores(frame, 12, invalid, &scores));
  invalid[0] = 12;
  assert(!ReadSenseVoiceLanguageScores(frame, 12, invalid, &scores));
  invalid[0] = ids[1];
  assert(!ReadSenseVoiceLanguageScores(frame, 12, invalid, &scores));
  assert(!ReadSenseVoiceLanguageScores(nullptr, 12, ids, &scores));
  assert(!ReadSenseVoiceLanguageScores(frame, 0, ids, &scores));
  assert(!ReadSenseVoiceLanguageScores(frame, 12, ids, nullptr));
}
