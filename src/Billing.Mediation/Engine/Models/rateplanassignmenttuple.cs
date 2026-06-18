// Ported VERBATIM from legacy Models_Mediation/rateplanassignmenttuple.cs (+ its EntityExtensions
// GetTuple helpers, folded in here). EF navigation props (billingruleassignment, route1, ratetaskassigns)
// REMOVED; the rateassigns collection is KEPT as the data the rater matches over (a tuple owns its
// rates). config-manager serves this shape (tuple + nested rateassigns) so .NET deserializes 1:1.
#nullable disable

namespace MediationModel
{
    using System.Collections.Generic;
    using System.Text;

    public partial class rateplanassignmenttuple
    {
        public rateplanassignmenttuple()
        {
            this.rateassigns = new List<rateassign>();
        }

        public int id { get; set; }
        public int idService { get; set; }
        public int AssignDirection { get; set; }
        public System.Nullable<int> idpartner { get; set; }
        public System.Nullable<int> route { get; set; }
        public int priority { get; set; }

        public ICollection<rateassign> rateassigns { get; set; }

        // --- legacy EntityExtensions: the resolution key forms (route-first, then partner, then service) ---

        private string GetServiceTupleWithoutPriority() => this.idService.ToString();

        private string GetPartnerTupleWithoutPriority() =>
            new StringBuilder(GetServiceTupleWithoutPriority()).Append("/")
                .Append(this.AssignDirection.ToString()).Append("/").Append(this.idpartner.ToString()).ToString();

        private string GetRouteTupleWithoutPriority() =>
            new StringBuilder(GetServiceTupleWithoutPriority()).Append("/")
                .Append(this.AssignDirection.ToString()).Append("/").Append(this.route.ToString()).ToString();

        public string GetTuple()
        {
            if (this.route != null && this.route > 0)
                return GetRouteTupleWithoutPriority();
            if (this.idpartner != null && this.idpartner > 0)
                return GetPartnerTupleWithoutPriority();
            return GetServiceTupleWithoutPriority();
        }
    }
}
